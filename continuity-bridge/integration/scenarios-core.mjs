import { readFile } from "node:fs/promises";
import { Assertions, accepted, status } from "./assertions.mjs";
import { ack, event, request, RoleConnection } from "./http-client.mjs";

const clip = (text) => ({ text });
const note = (sequence, body = "safe") => ({ notificationKey: `note-${sequence}`, packageName: "com.example.task7",
  appLabel: "Task 7", title: "Integration", body });

async function publish(config, actor, body) {
  return request(config, { token: actor.token, method: "POST", path: "/v1/events", body });
}

async function fetch(config, actor, after = "0", waitMs = 0) {
  return request(config, { token: actor.token, path: `/v1/events?after=${after}&waitMs=${waitMs}` });
}

async function acknowledge(config, actor, ids) {
  return request(config, { token: actor.token, method: "POST", path: "/v1/acks", body: ack(actor, ids) });
}

function assertedAck(a, name, response, ids) {
  status(a, name, response, 200);
  a.equal(`${name}.acked`, response.body.acked, ids);
  a.equal(`${name}.absent`, response.body.alreadyAbsent, []);
}

export async function runCore(config) {
  const a = new Assertions();
  const { android, macos } = config.active;
  const health = await request(config);
  status(a, "health", health, 200);
  a.equal("health.protocol", health.body.protocolVersion, 1);
  a.ok("health.epoch", /^[0-9a-f]{32}$/.test(health.body.serverEpoch));
  await a.rejects("tls.wrong-hostname", () => request(config, { servername: "wrong.invalid" }));
  const wrongCa = await readFile(new URL("../macos/Tests/ContinuityCoreTests/Fixtures/wrong-ca.pem", import.meta.url));
  await a.rejects("tls.wrong-ca", () => request(config, { ca: wrongCa }));

  const invalidAuth = [
    ["auth.missing", undefined], ["auth.bad", "task7-invalid-token"], ["auth.revoked", config.revoked],
  ];
  for (const [name, token] of invalidAuth) {
    const response = await request(config, { token, method: "POST", path: "/v1/events", raw: "{" });
    status(a, name, response, 401, "unauthorized");
  }

  const epochA = "task7-android-core-a";
  const first = event(android, { eventId: "task7-android-clip-1", originEpoch: epochA, sequence: 1,
    payload: clip("TASK7_PAYLOAD_SENTINEL_ANDROID_TO_MAC") });
  const firstResult = await publish(config, android, first);
  accepted(a, "android.clip.publish", firstResult, 201, "1");
  const firstFetch = await fetch(config, macos);
  status(a, "macos.clip.fetch", firstFetch, 200);
  a.equal("macos.clip.ids", firstFetch.body.events.map((entry) => entry.event.eventId), [first.eventId]);
  a.equal("macos.clip.payload", firstFetch.body.events[0].event.payload.text, first.payload.text);
  const firstAck = await acknowledge(config, macos, [first.eventId]);
  status(a, "macos.clip.ack", firstAck, 200);
  a.equal("macos.clip.acked", firstAck.body.acked, [first.eventId]);

  const orderEpoch = "task7-macos-order-a";
  const order3 = event(macos, { eventId: "task7-order-3", originEpoch: orderEpoch, sequence: 3, payload: clip("three") });
  accepted(a, "order.3", await publish(config, macos, order3), 201, "2");
  for (const [index, sequence] of [1, 2, 2].entries()) {
    const stale = event(macos, { eventId: `task7-order-${sequence}-${index}`, originEpoch: orderEpoch, sequence,
      payload: clip(`stale-${sequence}`) });
    status(a, `order.stale-${sequence}-${index}`, await publish(config, macos, stale), 409, "stale_sequence");
  }
  assertedAck(a, "order.ack", await acknowledge(config, android, [order3.eventId]), [order3.eventId]);

  const macEvent = event(macos, { eventId: "task7-macos-clip-1", originEpoch: "task7-macos-core-a", sequence: 1,
    payload: clip("TASK7_PAYLOAD_SENTINEL_MAC_TO_ANDROID") });
  accepted(a, "macos.clip.publish", await publish(config, macos, macEvent), 201, "3");
  const androidFetch = await fetch(config, android);
  status(a, "android.clip.fetch", androidFetch, 200);
  a.equal("android.clip.ids", androidFetch.body.events.map((entry) => entry.event.eventId), [macEvent.eventId]);
  a.equal("android.clip.payload", androidFetch.body.events[0].event.payload.text, macEvent.payload.text);
  assertedAck(a, "android.clip.ack", await acknowledge(config, android, [macEvent.eventId]), [macEvent.eventId]);

  const notification = event(android, { eventId: "task7-note-2", originEpoch: epochA, sequence: 2,
    kind: "android.notification", payload: note(2, "TASK7_PAYLOAD_SENTINEL_NOTIFICATION") });
  accepted(a, "notification.publish", await publish(config, android, notification), 201, "4");
  const noteFetch = await fetch(config, macos);
  status(a, "notification.fetch", noteFetch, 200);
  a.equal("notification.ids", noteFetch.body.events.map((entry) => entry.event.eventId), [notification.eventId]);
  a.equal("notification.kind", noteFetch.body.events[0].event.kind, "android.notification");
  a.equal("notification.direction", noteFetch.body.events[0].event.originRole, "android");
  assertedAck(a, "notification.ack", await acknowledge(config, macos, [notification.eventId]), [notification.eventId]);

  const forbidden = event(macos, { eventId: "task7-forbidden", originEpoch: "task7-macos-core-a", sequence: 2,
    kind: "android.notification", payload: note(2) });
  status(a, "direction.forbidden", await publish(config, macos, forbidden), 403, "direction_forbidden");
  const wrongIdentity = { ...first, eventId: "task7-wrong-role", sequence: 3 };
  status(a, "identity.wrong-role", await publish(config, macos, wrongIdentity), 403, "identity_mismatch");

  const replay = event(android, { eventId: "task7-replay-3", originEpoch: epochA, sequence: 3,
    kind: "android.notification", payload: note(3) });
  const replayAccepted = await publish(config, android, replay);
  accepted(a, "retry.first", replayAccepted, 201, "5");
  accepted(a, "retry.identical", await publish(config, android, { ...replay, ignored: "opaque" }), 200, "5", true);
  status(a, "retry.event-conflict", await publish(config, android, { ...replay, payload: note(3, "changed") }), 409, "event_id_conflict");
  status(a, "retry.sequence-conflict", await publish(config, android, { ...replay, eventId: "task7-seq-conflict",
    payload: note(3, "changed") }), 409, "sequence_conflict");
  const replayFetch = await fetch(config, macos, "0");
  const replayAgain = await fetch(config, macos, "0");
  status(a, "cursor.first", replayFetch, 200);
  status(a, "cursor.second", replayAgain, 200);
  a.equal("cursor.replay", replayAgain.body.events.map((entry) => entry.event.eventId), replayFetch.body.events.map((entry) => entry.event.eventId));
  a.equal("cursor.original", replayAgain.body.events[0].cursor, "5");
  assertedAck(a, "retry.ack", await acknowledge(config, macos, [replay.eventId]), [replay.eventId]);

  const reset = event(android, { eventId: "task7-epoch-reset", originEpoch: "task7-android-core-b", sequence: 1, payload: clip("reset") });
  accepted(a, "epoch.reset", await publish(config, android, reset), 201, "6");
  const retired = event(android, { eventId: "task7-retired", originEpoch: epochA, sequence: 4, payload: clip("retired") });
  status(a, "epoch.retired", await publish(config, android, retired), 409, "stale_sequence");
  assertedAck(a, "epoch.ack", await acknowledge(config, macos, [reset.eventId]), [reset.eventId]);

  const tailHealth = await request(config);
  status(a, "longpoll.tail-health", tailHealth, 200);
  const tail = tailHealth.body.tailCursor;
  const connection = new RoleConnection(config, macos);
  const held = connection.hold(tail);
  await a.rejects("connection.second-refused", async () => connection.hold(tail));
  held.cancel();
  await a.rejects("longpoll.cancelled", () => held.promise);
  const recovery = await fetch(config, macos, tail, 5);
  status(a, "longpoll.recovery", recovery, 200);
  a.equal("longpoll.recovery.empty", recovery.body.events, []);
  a.equal("connection.released", connection.active, false);
  return a.summary("core");
}

export { acknowledge, fetch, note, publish };
