import assert from "node:assert/strict";
import test from "node:test";
import { DurableStore } from "../src/store.mjs";
import { MetadataLogger } from "../src/logger.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, tempState } from "./helpers.mjs";

test("Given a waiter at tail When event arrives Then signal resolves without sleeping", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const controller = new AbortController();
  const waiting = store.waitForEvents(MACOS, "0", 25_000, controller.signal);
  // When
  await store.publish(ANDROID, clipboard());
  const fetched = await waiting;
  // Then
  assert.equal(fetched.events[0].event.eventId, "event-clipboard-1");
  assert.equal(store.waiterCount, 0);
});

test("Given an active long poll When client cancels Then waiter is released", async () => {
  // Given
  const fixture = await tempState();
  const store = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const controller = new AbortController();
  const waiting = store.waitForEvents(MACOS, "0", 25_000, controller.signal);
  // When
  controller.abort();
  await waiting;
  // Then
  assert.equal(store.waiterCount, 0);
});

test("Given metadata logger inputs and real Error When serialized Then type survives and sentinels do not", async () => {
  // Given
  const chunks = [];
  const sink = { write: (chunk) => chunks.push(chunk) };
  const logger = new MetadataLogger(sink);
  // When
  logger.info("event.accepted", { eventId: "safe-id", role: "android", kind: "clipboard.text", bytes: 24, payload: "PAYLOAD_SENTINEL", token: "TOKEN_SENTINEL" });
  logger.error("state.failure", new TypeError("safe failure"), { cursor: "2", authorization: "AUTH_SENTINEL" });
  const output = chunks.join("");
  // Then
  assert.match(output, /"errorClass":"TypeError"/);
  assert.doesNotMatch(output, /PAYLOAD_SENTINEL|TOKEN_SENTINEL|AUTH_SENTINEL/);
  assert.doesNotMatch(output, /safe failure/);
});
