import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import test from "node:test";
import { StateError } from "../src/errors.mjs";
import { atomicWrite } from "../src/persistence.mjs";
import { emptyState, fingerprint } from "../src/state-model.mjs";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, notification, tempState } from "./helpers.mjs";

test("Given identical and conflicting identities When publishing Then cursor reuse and 409 codes are stable", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const first = clipboard();
  // When
  const accepted = await store.publish(ANDROID, first);
  const retried = await store.publish(ANDROID, { ...first, ignoredUnknown: true });
  const idConflict = await store.publish(ANDROID, { ...first, payload: { text: "DIFFERENT" } });
  const sequenceConflict = await store.publish(ANDROID, { ...first, eventId: "different-id", payload: { text: "DIFFERENT" } });
  // Then
  assert.deepEqual([accepted.status, accepted.cursor, accepted.idempotent], [201, "1", false]);
  assert.deepEqual([retried.status, retried.cursor, retried.idempotent], [200, "1", true]);
  assert.deepEqual([idConflict.status, idConflict.code], [409, "event_id_conflict"]);
  assert.deepEqual([sequenceConflict.status, sequenceConflict.code], [409, "sequence_conflict"]);
});

test("Given concurrent publications When mutations race Then cursors and durable JSON are serialized", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const events = Array.from({ length: 32 }, (_, index) => notification(index + 1));
  // When
  const results = await Promise.all(events.map((event) => store.publish(ANDROID, event)));
  // Then
  assert.deepEqual(results.map((result) => Number(result.cursor)).sort((a, b) => a - b), Array.from({ length: 32 }, (_, i) => i + 1));
  const persisted = JSON.parse(await readFile(fixture.path, "utf8"));
  assert.equal(persisted.nextCursor, "33");
  assert.equal(persisted.retained.length, 32);
});

test("Given unacked clipboard values When a newer one arrives Then only latest remains and ack removes it", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  await store.publish(ANDROID, clipboard());
  // When
  await store.publish(ANDROID, clipboard({ eventId: "event-clipboard-2", sequence: 2, payload: { text: "LATEST" } }));
  const beforeAck = await store.fetch(MACOS, "0");
  const ack = await store.ack(MACOS, ["event-clipboard-2"]);
  const afterAck = await store.fetch(MACOS, "0");
  // Then
  assert.deepEqual(beforeAck.events.map((entry) => entry.event.eventId), ["event-clipboard-2"]);
  assert.deepEqual(ack, { status: 200, acked: ["event-clipboard-2"], alreadyAbsent: [] });
  assert.deepEqual(afterAck.events, []);
});

test("Given persisted relay state and stale temp When reopened Then epoch cursor and retained event recover", async () => {
  // Given
  const fixture = await tempState();
  const first = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const epoch = first.serverEpoch;
  await first.publish(ANDROID, notification(1));
  await writeFile(`${fixture.path}.stale.tmp`, "invalid stale temp");
  // When
  const reopened = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const fetched = await reopened.fetch(MACOS, "0");
  // Then
  assert.equal(reopened.serverEpoch, epoch);
  assert.equal(fetched.nextCursor, "1");
  assert.equal(fetched.events[0].event.eventId, "event-notification-1");
});

test("Given corrupt durable state When opened Then startup fails closed without reset", async () => {
  // Given
  const fixture = await tempState();
  await writeFile(fixture.path, "{corrupt", { mode: 0o600 });
  // When / Then
  await assert.rejects(() => DurableStore.open({ statePath: fixture.path, clock: fixedClock() }), { name: "StateError" });
  assert.equal(await readFile(fixture.path, "utf8"), "{corrupt");
});

test("Given structurally corrupt durable metadata When opened Then startup fails closed", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const state = JSON.parse(await readFile(fixture.path, "utf8"));
  state.dedupe = [{ eventId: 7, originKey: null, sequence: "one" }];
  await writeFile(fixture.path, JSON.stringify(state), { mode: 0o600 });
  // When / Then
  await assert.rejects(() => DurableStore.open({ statePath: fixture.path, clock: fixedClock() }), { name: "StateError" });
  assert.match(store.serverEpoch, /^[0-9a-f]{32}$/);
});

test("Given a persistence failure When an event is retried Then the live store never reports an uncommitted success", async () => {
  // Given
  const fixture = await tempState();
  let writes = 0;
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock(), writeState: async (path, state) => {
    writes += 1;
    if (writes === 1) return atomicWrite(path, state);
    throw new StateError(new Error("injected_write_failure"));
  } });
  // When / Then
  await assert.rejects(() => store.publish(ANDROID, clipboard()), { name: "StateError" });
  await assert.rejects(() => store.publish(ANDROID, clipboard()), { name: "StateError" });
  assert.equal(writes, 2);
  const reopened = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  assert.deepEqual((await reopened.fetch(MACOS, "0")).events, []);
});

test("Given a retained clipboard at the dedupe limit When newer history arrives Then restart preserves the pending event", async () => {
  // Given
  const fixture = await tempState();
  const retained = clipboard();
  const state = emptyState();
  state.nextCursor = "4097";
  state.retained = [{ cursor: "1", destination: "macos", event: retained,
    bytes: Buffer.byteLength(JSON.stringify(retained), "utf8"), deadlineMs: null }];
  state.highWaters = [{ key: `${ANDROID.deviceId}\u0000${retained.originEpoch}`, sequence: 4096 }];
  state.activeEpochs = [{ deviceId: ANDROID.deviceId, epoch: retained.originEpoch }];
  state.dedupe = Array.from({ length: 4096 }, (_, index) => ({
    eventId: index === 0 ? retained.eventId : `history-${index + 1}`,
    originKey: `${ANDROID.deviceId}\u0000${retained.originEpoch}`,
    sequence: index + 1,
    fingerprint: index === 0 ? fingerprint(retained) : "0".repeat(64),
    cursor: String(index + 1), originRole: "android",
  }));
  await atomicWrite(fixture.path, state);
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // When
  await store.publish(ANDROID, notification(4097));
  const reopened = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // Then
  assert.deepEqual((await reopened.fetch(MACOS, "0")).events.map((entry) => entry.event.eventId),
    [retained.eventId, "event-notification-4097"]);
});
