import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { Authenticator } from "../src/auth.mjs";
import { MetadataLogger } from "../src/logger.mjs";
import { createRelayServer, close, listen } from "../src/server.mjs";
import { DurableStore } from "../src/store.mjs";
import { clipboard, makeTls, request, tempState } from "./helpers.mjs";

const sha256 = (value) => createHash("sha256").update(value, "utf8").digest("hex");

test("Given authenticated identity-mismatched event and ack requests When the live HTTPS boundary rejects them Then one safe discriminator record is emitted for each", async (context) => {
  // Given
  const fixture = await tempState();
  const tls = await makeTls(fixture.directory);
  const actor = Object.freeze({ role: "android", deviceId: "ACTOR_DEVICE_SENTINEL" });
  const token = "BEARER_TOKEN_SENTINEL";
  const log = [];
  const logger = new MetadataLogger({ write: (line) => log.push(line) });
  const store = await DurableStore.open({ statePath: fixture.path });
  const authenticator = new Authenticator([{ token, ...actor, revoked: false }]);
  const server = createRelayServer({ tls: { cert: await readFile(tls.cert), key: await readFile(tls.key), minVersion: "TLSv1.2" }, store, authenticator, logger });
  context.after(() => close(server));
  const address = await listen(server, "127.0.0.1", 0);
  const eventDeviceId = "EVENT_DEVICE_SENTINEL";
  const ackDeviceId = "ACK_DEVICE_SENTINEL";
  const eventPayload = "EVENT_PAYLOAD_SENTINEL";
  const epoch = "EPOCH_SENTINEL";

  // When
  const rejectedEvent = await request({ port: address.port, ca: tls.ca, token, method: "POST", path: "/v1/events?query_secret=QUERY_SENTINEL",
    body: clipboard({ originDeviceId: eventDeviceId, originEpoch: epoch, payload: { text: eventPayload } }) });
  const rejectedAck = await request({ port: address.port, ca: tls.ca, token, method: "POST", path: "/v1/acks?query_secret=QUERY_SENTINEL",
    body: { protocolVersion: 1, recipientRole: "android", recipientDeviceId: ackDeviceId, eventIds: ["ack-event"] } });
  const injectedOriginRole = "android\nUNTRUSTED_ROLE_SENTINEL";
  const injectedKind = "clipboard.text|UNTRUSTED_KIND_SENTINEL";
  const rejectedInjectedEvent = await request({ port: address.port, ca: tls.ca, token, method: "POST", path: "/v1/events",
    body: clipboard({ originDeviceId: "INJECTED_EVENT_DEVICE_SENTINEL", originRole: injectedOriginRole, kind: injectedKind }) });

  // Then
  assert.deepEqual([rejectedEvent.status, rejectedEvent.body.error.code], [403, "identity_mismatch"]);
  assert.deepEqual([rejectedAck.status, rejectedAck.body.error.code], [403, "identity_mismatch"]);
  assert.deepEqual([rejectedInjectedEvent.status, rejectedInjectedEvent.body.error.code], [400, "unsupported_kind"]);
  const records = log.map((line) => JSON.parse(line)).filter((entry) => entry.event === "request.rejected");
  assert.equal(records.length, 3, "each rejected request must emit exactly one request.rejected record");
  assert.deepEqual(records[0], { level: "warn", event: "request.rejected", method: "POST", path: "/v1/events", status: 403,
    errorCode: "identity_mismatch", role: "android", actorDeviceIdSha256: sha256(actor.deviceId), bodyDeviceIdSha256: sha256(eventDeviceId),
    identityMatch: false, originRole: "android", kind: "clipboard.text" });
  assert.deepEqual(records[1], { level: "warn", event: "request.rejected", method: "POST", path: "/v1/acks", status: 403,
    errorCode: "identity_mismatch", role: "android", actorDeviceIdSha256: sha256(actor.deviceId), bodyDeviceIdSha256: sha256(ackDeviceId),
    identityMatch: false });
  assert.deepEqual(records[2], { level: "warn", event: "request.rejected", method: "POST", path: "/v1/events", status: 400,
    errorCode: "unsupported_kind", role: "android", actorDeviceIdSha256: sha256(actor.deviceId),
    bodyDeviceIdSha256: sha256("INJECTED_EVENT_DEVICE_SENTINEL"), identityMatch: false });
  const output = log.join("");
  assert.doesNotMatch(output, /BEARER_TOKEN_SENTINEL|ACTOR_DEVICE_SENTINEL|EVENT_DEVICE_SENTINEL|ACK_DEVICE_SENTINEL|INJECTED_EVENT_DEVICE_SENTINEL|EVENT_PAYLOAD_SENTINEL|EPOCH_SENTINEL|QUERY_SENTINEL|UNTRUSTED_ROLE_SENTINEL|UNTRUSTED_KIND_SENTINEL|Authorization|Bearer/);
});
