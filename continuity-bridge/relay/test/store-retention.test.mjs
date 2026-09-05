import assert from "node:assert/strict";
import { readFile, writeFile } from "node:fs/promises";
import test from "node:test";
import { emptyState, MAX_REPLAY_ORIGIN_KEYS } from "../src/state-model.mjs";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, fixedClock, notification, tempState } from "./helpers.mjs";

test("Given a notification at the retention boundary When clock advances Then relay TTL expires at 15 minutes", async () => {
  // Given
  const fixture = await tempState();
  const clock = fixedClock();
  const store = await DurableStore.open({ statePath: fixture.path, clock });
  await store.publish(ANDROID, notification(1, { expiresAtMs: undefined }));
  // When
  clock.advance(899_999);
  const before = await store.fetch(MACOS, "0");
  clock.advance(1);
  const after = await store.fetch(MACOS, "0");
  // Then
  assert.equal(before.events.length, 1);
  assert.equal(after.events.length, 0);
});

test("Given 101 notification events When persisted Then oldest cursor is evicted", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // When
  for (let sequence = 1; sequence <= 101; sequence += 1) await store.publish(ANDROID, notification(sequence));
  const fetched = await store.fetch(MACOS, "0");
  // Then
  assert.equal(fetched.events.length, 100);
  assert.equal(fetched.events[0].event.eventId, "event-notification-2");
});

test("Given large notification events When byte pool exceeds 512KiB Then oldest cursors are evicted", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // When
  for (let sequence = 1; sequence <= 9; sequence += 1) {
    await store.publish(ANDROID, notification(sequence, { payload: {
      notificationKey: `key-${sequence}`, packageName: "com.example.harmless", appLabel: "Harmless", title: "", body: "x".repeat(65_000),
    } }));
  }
  const fetched = await store.fetch(MACOS, "0");
  // Then
  assert.ok(fetched.events.length < 9);
  assert.ok(fetched.events.reduce((sum, entry) => sum + entry.bytes, 0) <= 524_288);
  assert.notEqual(fetched.events[0].event.eventId, "event-notification-1");
});

test("Given one active epoch When a new epoch starts at one Then old epoch traffic is stale", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  await store.publish(ANDROID, notification(1));
  // When
  const nextEpoch = await store.publish(ANDROID, notification(1, { eventId: "new-epoch", originEpoch: "epoch-android-b" }));
  const retired = await store.publish(ANDROID, notification(2, { eventId: "retired", originEpoch: "epoch-android-a" }));
  // Then
  assert.equal(nextEpoch.status, 201);
  assert.deepEqual([retired.status, retired.code], [409, "stale_sequence"]);
});

test("Given one persisted origin epoch When replayed or conflicted Then ordering protection survives restore", async () => {
  // Given
  const fixture = await tempState();
  const first = notification(1);
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const accepted = await store.publish(ANDROID, first);
  const restored = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // When
  const replay = await restored.publish(ANDROID, first);
  const conflict = await restored.publish(ANDROID, notification(1, { eventId: "conflicting-event" }));
  // Then
  assert.deepEqual([accepted.status, replay.status, replay.idempotent], [201, 200, true]);
  assert.deepEqual([conflict.status, conflict.code], [409, "sequence_conflict"]);
});

test("Given the maximum replay origin keys When the active key advances Then all keys remain protected", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  for (let index = 0; index < MAX_REPLAY_ORIGIN_KEYS; index += 1) {
    const result = await store.publish(ANDROID, notification(1, {
      eventId: `bounded-epoch-${index}`, originEpoch: `bounded-epoch-${index}`,
    }));
    assert.equal(result.status, 201);
  }
  const restored = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // When
  const existing = await restored.publish(ANDROID, notification(2, {
    eventId: "bounded-existing-update", originEpoch: `bounded-epoch-${MAX_REPLAY_ORIGIN_KEYS - 1}`,
  }));
  // Then
  const persisted = JSON.parse(await readFile(fixture.path, "utf8"));
  assert.equal(existing.status, 201);
  assert.equal(persisted.highWaters.length, MAX_REPLAY_ORIGIN_KEYS);
  assert.equal(persisted.retiredEpochs.length, MAX_REPLAY_ORIGIN_KEYS - 1);
  assert.equal(persisted.highWaters.at(-1).sequence, 2);
});

test("Given full replay origin metadata When another epoch arrives Then relay rejects without mutation", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  for (let index = 0; index < MAX_REPLAY_ORIGIN_KEYS; index += 1) {
    await store.publish(ANDROID, notification(1, {
      eventId: `full-epoch-${index}`, originEpoch: `full-epoch-${index}`,
    }));
  }
  const before = JSON.parse(await readFile(fixture.path, "utf8"));
  // When
  const rejected = await store.publish(ANDROID, notification(1, {
    eventId: "over-limit-epoch", originEpoch: "over-limit-epoch",
  }));
  // Then
  const after = JSON.parse(await readFile(fixture.path, "utf8"));
  assert.deepEqual([rejected.status, rejected.code], [409, "stale_sequence"]);
  assert.deepEqual(after, before);
  assert.equal(after.highWaters.length, MAX_REPLAY_ORIGIN_KEYS);
});

test("Given oversized persisted replay arrays When relay restores Then it fails closed", async () => {
  for (const field of ["highWaters", "retiredEpochs"]) {
    // Given
    const fixture = await tempState();
    const state = emptyState();
    state[field] = Array.from({ length: MAX_REPLAY_ORIGIN_KEYS + 1 }, (_, index) => field === "highWaters"
      ? { key: `device\u0000epoch-${index}`, sequence: 1 }
      : `device\u0000epoch-${index}`);
    await writeFile(fixture.path, JSON.stringify(state));
    // When / Then
    await assert.rejects(DurableStore.open({ statePath: fixture.path, clock: fixedClock() }));
  }

  for (const field of ["highWaters", "retiredEpochs"]) {
    // Given
    const fixture = await tempState();
    const state = emptyState();
    const entry = field === "highWaters" ? { key: "device\u0000duplicate", sequence: 1 } : "device\u0000duplicate";
    state[field] = [entry, structuredClone(entry)];
    await writeFile(fixture.path, JSON.stringify(state));
    // When / Then
    await assert.rejects(DurableStore.open({ statePath: fixture.path, clock: fixedClock() }));
  }
});
