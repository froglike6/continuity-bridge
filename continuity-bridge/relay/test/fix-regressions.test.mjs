import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, request, startProcess, stopProcess, tempState } from "./helpers.mjs";

const hashState = async (path) => createHash("sha256").update(await readFile(path)).digest("hex");
const ackBody = (role, deviceId, eventIds) => ({ protocolVersion: 1, recipientRole: role, recipientDeviceId: deviceId, eventIds });
const macClipboard = (sequence, eventId) => clipboard({ eventId, originDeviceId: MACOS.deviceId, originRole: MACOS.role,
  originEpoch: "epoch-macos-a", sequence });

test("Given non-finite JSON numbers anywhere When event or ack bodies cross HTTPS Then malformed_json leaves state byte-identical", async (context) => {
  // Given
  const runtime = await startProcess();
  context.after(() => stopProcess(runtime.child));
  const event = JSON.stringify(clipboard());
  const eventCases = [
    event.replace('"createdAtMs":1700000000000', '"createdAtMs":1e400'),
    `${event.slice(0, -1)},"unknown":1e400}`,
    `${event.slice(0, -1)},"unknown":{"items":[0,{"value":1e400}]}}`,
    `${event.slice(0, -1)},"unknown":[${"0,".repeat(130_000)}1e400]}`,
    event.replace('"payload":{"text":"HARMLESS_CLIPBOARD"}', '"payload":{"text":"HARMLESS_CLIPBOARD","unknown":1e400}'),
  ];
  // When
  const observations = [];
  for (const body of eventCases) {
    const before = await hashState(runtime.fixture.path);
    const result = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android,
      method: "POST", path: "/v1/events", body });
    observations.push([result.status, result.body.error?.code, await hashState(runtime.fixture.path) === before]);
  }
  const ack = JSON.stringify({ ...ackBody(MACOS.role, MACOS.deviceId, ["absent"]), unknown: { values: [1] } })
    .replace('"values":[1]', '"values":[1e400]');
  const beforeAck = await hashState(runtime.fixture.path);
  const result = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.macos,
    method: "POST", path: "/v1/acks", body: ack });
  observations.push([result.status, result.body.error?.code, await hashState(runtime.fixture.path) === beforeAck]);
  // Then
  assert.deepEqual(observations, Array.from({ length: 6 }, () => [400, "malformed_json", true]));
});

test("Given finite numbers in opaque fields When JSON crosses HTTPS Then valid schema fields still decide acceptance", async (context) => {
  // Given
  const runtime = await startProcess();
  context.after(() => stopProcess(runtime.child));
  const body = `${JSON.stringify(clipboard()).slice(0, -1)},"unknown":{"minimum":-1e308,"maximum":1e308}}`;
  // When
  const result = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android,
    method: "POST", path: "/v1/events", body });
  // Then
  assert.deepEqual([result.status, result.body.cursor], [201, "1"]);
});

test("Given retained events for both recipients When cross-role same-role unknown and batched acks occur Then authorization is recipient-bound and atomic", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  await store.publish(ANDROID, clipboard({ eventId: "android-for-mac-1" }));
  await store.publish(MACOS, macClipboard(1, "mac-for-android-1"));
  // When / Then: both valid recipient directions remove their event.
  assert.deepEqual(await store.ack(MACOS, ["android-for-mac-1"]),
    { status: 200, acked: ["android-for-mac-1"], alreadyAbsent: [] });
  assert.deepEqual(await store.ack(ANDROID, ["mac-for-android-1"]),
    { status: 200, acked: ["mac-for-android-1"], alreadyAbsent: [] });
  assert.deepEqual((await store.fetch(MACOS, "0")).events, []);
  assert.deepEqual((await store.fetch(ANDROID, "0")).events, []);

  await store.publish(ANDROID, clipboard({ eventId: "android-for-mac-2", sequence: 2 }));
  await store.publish(MACOS, macClipboard(2, "mac-for-android-2"));
  const beforeDenied = await hashState(fixture.path);
  assert.deepEqual(await store.ack(ANDROID, ["android-for-mac-2"]), { status: 403, code: "identity_mismatch" });
  assert.equal(await hashState(fixture.path), beforeDenied);
  assert.deepEqual(await store.ack(MACOS, ["android-for-mac-2", "mac-for-android-2"]),
    { status: 403, code: "identity_mismatch" });
  assert.equal(await hashState(fixture.path), beforeDenied);
  assert.equal((await store.fetch(MACOS, "0")).events.length, 1);
  assert.equal((await store.fetch(ANDROID, "0")).events.length, 1);

  assert.deepEqual(await store.ack(MACOS, ["android-for-mac-2", "unknown-event"]),
    { status: 200, acked: ["android-for-mac-2"], alreadyAbsent: ["unknown-event"] });
  assert.deepEqual((await store.fetch(MACOS, "0")).events, []);
  assert.equal((await DurableStore.open({ statePath: fixture.path, clock: fixedClock() }).then((reopened) => reopened.fetch(MACOS, "0"))).events.length, 0);
});
