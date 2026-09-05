import assert from "node:assert/strict";
import test from "node:test";
import { parseStrictJson } from "../src/strict-json.mjs";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, fixedClock, tempState } from "./helpers.mjs";

test("Given duplicate members malformed UTF-8 and trailing data When JSON crosses boundary Then all reject", () => {
  // Given
  const invalidUtf8 = Buffer.from([0x7b, 0x22, 0x78, 0x22, 0x3a, 0x22, 0xc3, 0x28, 0x22, 0x7d]);
  const cases = [Buffer.from('{"x":1,"x":2}'), invalidUtf8, Buffer.from('{"x":1} trailing')];
  // When / Then
  for (const input of cases) assert.throws(() => parseStrictJson(input));
});

test("Given a tail cursor and bounded wait When no event arrives Then timeout returns empty and releases waiter", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  // When
  const result = await store.waitForEvents(MACOS, "0", 10, new AbortController().signal);
  // Then
  assert.deepEqual(result.events, []);
  assert.equal(result.nextCursor, "0");
  assert.equal(store.waiterCount, 0);
});

test("Given interleaved publish and ack mutations When concurrent Then durable outcome has no lost updates", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const first = { protocolVersion: 1, eventId: "interleave-1", originDeviceId: ANDROID.deviceId, originRole: "android",
    originEpoch: "interleave", sequence: 1, kind: "clipboard.text", createdAtMs: 0, payload: { text: "one" } };
  await store.publish(ANDROID, first);
  const second = { ...first, eventId: "interleave-2", sequence: 2, payload: { text: "two" } };
  // When
  const [publishResult, ackResult] = await Promise.all([store.publish(ANDROID, second), store.ack(MACOS, ["interleave-1"])]);
  const fetched = await store.fetch(MACOS, "0");
  // Then
  assert.equal(publishResult.status, 201);
  assert.deepEqual(ackResult.alreadyAbsent, ["interleave-1"]);
  assert.deepEqual(fetched.events.map((entry) => entry.event.eventId), ["interleave-2"]);
});
