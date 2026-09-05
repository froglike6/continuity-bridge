import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import test from "node:test";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, request, startProcess, stopProcess, tempState } from "./helpers.mjs";

const digestPattern = /^[0-9a-f]{64}$/;
const sentinelEvent = (overrides = {}) => clipboard({ eventId: "privacy-event", payload: { text: "PRIVATE_DEDUPE_SENTINEL" }, ...overrides });

test("Given an acked event When durable dedupe remains Then only a digest persists and retry semantics survive restart", async () => {
  // Given
  const fixture = await tempState();
  let store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const event = sentinelEvent();
  const accepted = await store.publish(ANDROID, event);
  await store.ack(MACOS, [event.eventId]);
  // When
  const bytesAfterAck = await readFile(fixture.path, "utf8");
  const stateAfterAck = JSON.parse(bytesAfterAck);
  store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const retried = await store.publish(ANDROID, { payload: event.payload, ...event });
  const conflict = await store.publish(ANDROID, { ...event, payload: { text: "DIFFERENT" } });
  // Then
  assert.equal(bytesAfterAck.includes("PRIVATE_DEDUPE_SENTINEL"), false);
  assert.equal(stateAfterAck.retained.length, 0);
  assert.equal(stateAfterAck.dedupe.length, 1);
  assert.match(stateAfterAck.dedupe[0].fingerprint, digestPattern);
  assert.deepEqual([retried.status, retried.cursor, retried.idempotent], [200, accepted.cursor, true]);
  assert.deepEqual([conflict.status, conflict.code], [409, "event_id_conflict"]);
});

test("Given canonical legacy dedupe When reopened Then it atomically migrates before serving", async () => {
  // Given
  const fixture = await tempState();
  let store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const event = sentinelEvent({ eventId: "legacy-event" });
  await store.publish(ANDROID, event);
  await store.ack(MACOS, [event.eventId]);
  const legacy = JSON.parse(await readFile(fixture.path, "utf8"));
  legacy.dedupe[0].fingerprint = JSON.stringify(event);
  await writeFile(fixture.path, JSON.stringify(legacy), { mode: 0o600 });
  // When
  store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const migratedBytes = await readFile(fixture.path, "utf8");
  const migrated = JSON.parse(migratedBytes);
  const retry = await store.publish(ANDROID, event);
  // Then
  assert.equal(migratedBytes.includes("PRIVATE_DEDUPE_SENTINEL"), false);
  assert.match(migrated.dedupe[0].fingerprint, digestPattern);
  assert.deepEqual([retry.status, retry.cursor, retry.idempotent], [200, "1", true]);
});

test("Given invalid or retained-event-mismatched digests When reopened Then state fails closed", async () => {
  // Given
  const invalidFixture = await tempState();
  const invalidStore = await DurableStore.open({ statePath: invalidFixture.path, clock: fixedClock() });
  await invalidStore.publish(ANDROID, sentinelEvent({ eventId: "invalid-digest" }));
  const invalid = JSON.parse(await readFile(invalidFixture.path, "utf8"));
  invalid.dedupe[0].fingerprint = "not-a-digest";
  await writeFile(invalidFixture.path, JSON.stringify(invalid), { mode: 0o600 });

  const mismatchFixture = await tempState();
  const mismatchStore = await DurableStore.open({ statePath: mismatchFixture.path, clock: fixedClock() });
  await mismatchStore.publish(ANDROID, sentinelEvent({ eventId: "mismatched-digest" }));
  const mismatch = JSON.parse(await readFile(mismatchFixture.path, "utf8"));
  mismatch.dedupe[0].fingerprint = "0".repeat(64);
  await writeFile(mismatchFixture.path, JSON.stringify(mismatch), { mode: 0o600 });
  // When / Then
  await assert.rejects(() => DurableStore.open({ statePath: invalidFixture.path, clock: fixedClock() }), { name: "StateError" });
  await assert.rejects(() => DurableStore.open({ statePath: mismatchFixture.path, clock: fixedClock() }), { name: "StateError" });
});

test("Given live HTTPS publish and recipient ack When state is inspected Then raw payload is absent and retry/conflict remain exact", async (context) => {
  // Given
  const runtime = await startProcess();
  context.after(() => stopProcess(runtime.child));
  const event = sentinelEvent();
  // When
  const published = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android,
    method: "POST", path: "/v1/events", body: event });
  const acked = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.macos,
    method: "POST", path: "/v1/acks", body: { protocolVersion: 1, recipientRole: MACOS.role,
      recipientDeviceId: MACOS.deviceId, eventIds: [event.eventId] } });
  const bytes = await readFile(runtime.fixture.path, "utf8");
  const state = JSON.parse(bytes);
  const retry = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android,
    method: "POST", path: "/v1/events", body: event });
  const conflict = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android,
    method: "POST", path: "/v1/events", body: { ...event, payload: { text: "DIFFERENT" } } });
  // Then
  assert.deepEqual([published.status, acked.status, retry.status, retry.body.cursor], [201, 200, 200, "1"]);
  assert.deepEqual([conflict.status, conflict.body.error.code], [409, "event_id_conflict"]);
  assert.equal(bytes.includes("PRIVATE_DEDUPE_SENTINEL"), false);
  assert.match(state.dedupe[0].fingerprint, digestPattern);
});
