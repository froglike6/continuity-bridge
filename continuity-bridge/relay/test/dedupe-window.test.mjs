import assert from "node:assert/strict";
import { readFile, rm } from "node:fs/promises";
import test from "node:test";
import { atomicWrite } from "../src/persistence.mjs";
import { emptyState, fingerprint } from "../src/state-model.mjs";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, notification, tempState } from "./helpers.mjs";

async function retryWindow(context, retained = clipboard()) {
  const fixture = await tempState();
  context.after(() => rm(fixture.directory, { recursive: true, force: true }));
  const clock = fixedClock();
  const state = emptyState();
  const key = `${ANDROID.deviceId}\u0000${retained.originEpoch}`;
  state.nextCursor = "4097";
  state.retained = [{ cursor: "1", destination: "macos", event: retained,
    bytes: Buffer.byteLength(JSON.stringify(retained)),
    deadlineMs: retained.kind === "android.notification" ? clock.now() + 1 : null }];
  state.highWaters = [{ key, sequence: 4096 }];
  state.activeEpochs = [{ deviceId: ANDROID.deviceId, epoch: retained.originEpoch }];
  state.dedupe = Array.from({ length: 4096 }, (_, index) => {
    const event = index === 0 ? retained : notification(index + 1);
    return { eventId: event.eventId, originKey: key, sequence: event.sequence,
      fingerprint: fingerprint(event), cursor: String(index + 1), originRole: event.originRole };
  });
  await atomicWrite(fixture.path, state);
  const store = await DurableStore.open({ statePath: fixture.path, clock });
  await store.publish(ANDROID, notification(4097));
  return { fixture, clock, retained, store };
}

test("Given an old retained identity When retrying the oldest of 4096 recent acceptances after restart Then both preserve their original cursor", async (context) => {
  // Given
  const { fixture, clock, retained } = await retryWindow(context);
  const store = await DurableStore.open({ statePath: fixture.path, clock });
  // When
  const recentRetry = await store.publish(ANDROID, notification(2));
  const retainedRetry = await store.publish(ANDROID, retained);
  const fetched = await store.fetch(MACOS, "0");
  // Then
  assert.deepEqual(recentRetry, { status: 200, cursor: "2", idempotent: true });
  assert.deepEqual(retainedRetry, { status: 200, cursor: "1", idempotent: true });
  assert.deepEqual(fetched.events.map((entry) => entry.event.eventId), [retained.eventId, "event-notification-4097"]);
});

test("Given an old retained identity When the oldest recent acceptance conflicts Then its ID and sequence remain protected", async (context) => {
  // Given
  const { store } = await retryWindow(context);
  // When
  const sameId = await store.publish(ANDROID, notification(2, { payload: {
    ...notification(2).payload, body: "changed",
  } }));
  const sameSequence = await store.publish(ANDROID, notification(2, { eventId: "different-id" }));
  // Then
  assert.deepEqual(sameId, { status: 409, code: "event_id_conflict" });
  assert.deepEqual(sameSequence, { status: 409, code: "sequence_conflict" });
});

test("Given a retained identity outside the recent window When acknowledged Then only the newest 4096 fingerprints remain", async (context) => {
  // Given
  const { fixture, store, retained } = await retryWindow(context);
  // When
  await store.ack(MACOS, [retained.eventId]);
  const state = JSON.parse(await readFile(fixture.path, "utf8"));
  // Then
  assert.equal(state.dedupe.length, 4096);
  assert.equal(state.dedupe.some((entry) => entry.eventId === retained.eventId), false);
  assert.equal(state.dedupe[0].cursor, "2");
});

test("Given a retained notification outside the recent window When it expires Then fetch removes its extra fingerprint", async (context) => {
  // Given
  const { fixture, clock, store, retained } = await retryWindow(context, notification(1));
  // When
  clock.advance(1);
  await store.fetch(MACOS, "0");
  const state = JSON.parse(await readFile(fixture.path, "utf8"));
  // Then
  assert.equal(state.dedupe.length, 4096);
  assert.equal(state.dedupe.some((entry) => entry.eventId === retained.eventId), false);
  assert.equal(state.dedupe[0].cursor, "2");
});

test("Given an old retained identity When the retry window advances again Then only the newly out-of-window retry becomes stale", async (context) => {
  // Given
  const { store, retained } = await retryWindow(context);
  // When
  await store.publish(ANDROID, notification(4098));
  const expiredRetry = await store.publish(ANDROID, notification(2));
  const oldestRecent = await store.publish(ANDROID, notification(3));
  const pendingRetry = await store.publish(ANDROID, retained);
  // Then
  assert.deepEqual(expiredRetry, { status: 409, code: "stale_sequence" });
  assert.deepEqual(oldestRecent, { status: 200, cursor: "3", idempotent: true });
  assert.deepEqual(pendingRetry, { status: 200, cursor: "1", idempotent: true });
});
