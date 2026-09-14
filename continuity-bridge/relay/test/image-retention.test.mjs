import assert from "node:assert/strict";
import test from "node:test";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, notification, tempState } from "./helpers.mjs";

const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jkWQAAAAASUVORK5CYII=";
const image = (overrides = {}) => clipboard({ kind: "clipboard.image", payload: { mimeType: "image/png", dataBase64: PNG }, ...overrides });

for (const [first, second] of [[clipboard(), image({ sequence: 2, eventId: "image-2" })],
  [image(), clipboard({ sequence: 2, eventId: "text-2" })]]) {
  test(`Given retained ${first.kind} When newer ${second.kind} arrives Then one latest clipboard survives restart`, async () => {
    // Given
    const fixture = await tempState(); const store = await DurableStore.open({ statePath: fixture.path });
    assert.equal((await store.publish(ANDROID, first)).status, 201);
    // When
    const published = await store.publish(ANDROID, second);
    const restarted = await DurableStore.open({ statePath: fixture.path });
    // Then
    assert.equal(published.status, 201);
    assert.deepEqual((await restarted.fetch(MACOS, "0")).events.map((entry) => entry.event.eventId), [second.eventId]);
  });
}

test("Given an image and notification When notification retention expires Then the image remains", async () => {
  // Given
  const fixture = await tempState(); const clock = fixedClock();
  const store = await DurableStore.open({ statePath: fixture.path, clock });
  assert.equal((await store.publish(ANDROID, image({ expiresAtMs: clock.now() }))).status, 201);
  await store.publish(ANDROID, notification(2));
  // When
  clock.advance(900_000);
  const result = await store.fetch(MACOS, "0");
  // Then
  assert.deepEqual(result.events.map((entry) => entry.event.kind), ["clipboard.image"]);
});

test("Given clipboards from distinct senders When one sender changes type Then the other sender retains its latest value", async () => {
  // Given
  const fixture = await tempState(); const store = await DurableStore.open({ statePath: fixture.path });
  const other = { ...ANDROID, deviceId: "second-android" };
  await store.publish(ANDROID, clipboard());
  await store.publish(other, clipboard({ originDeviceId: other.deviceId, eventId: "other-text" }));
  // When
  const posted = await store.publish(ANDROID, image({ sequence: 2, eventId: "my-image" }));
  // Then
  assert.equal(posted.status, 201);
  assert.deepEqual((await store.fetch(MACOS, "0")).events.map((entry) => entry.event.eventId), ["other-text", "my-image"]);
});

test("Given two maximum images for one recipient When fetched Then bounded pages advance without skipping an image", async () => {
  // Given
  const fixture = await tempState(); const store = await DurableStore.open({ statePath: fixture.path });
  const bytes = Buffer.alloc(8_388_608); Buffer.from(PNG, "base64").copy(bytes);
  const payload = { mimeType: "image/png", dataBase64: bytes.toString("base64") };
  const other = { ...ANDROID, deviceId: "second-android" };
  assert.equal((await store.publish(ANDROID, image({ payload }))).status, 201);
  assert.equal((await store.publish(other, image({ originDeviceId: other.deviceId, eventId: "other-image", payload }))).status, 201);
  // When
  const first = await store.fetch(MACOS, "0"); const second = await store.fetch(MACOS, first.nextCursor);
  // Then
  assert.deepEqual(first.events.map((entry) => entry.event.eventId), ["event-clipboard-1"]);
  assert.equal(first.nextCursor, "1");
  assert.deepEqual(second.events.map((entry) => entry.event.eventId), ["other-image"]);
  assert.equal(second.nextCursor, "2");
  for (const page of [first, second]) assert.ok(Buffer.byteLength(JSON.stringify(page)) <= 12_582_912);
});
