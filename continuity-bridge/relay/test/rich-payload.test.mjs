import assert from "node:assert/strict";
import test from "node:test";
import { LIMITS, parseEvent } from "../src/schema.mjs";
import { ANDROID, MACOS, clipboard, notification, request, startProcess, stopProcess } from "./helpers.mjs";

export const PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jkWQAAAAASUVORK5CYII=";
export function imageEvent(overrides = {}) {
  return clipboard({ kind: "clipboard.image", payload: { mimeType: "image/png", dataBase64: PNG }, ...overrides });
}
export function paddedPng(size) {
  const data = Buffer.alloc(size);
  Buffer.from(PNG, "base64").copy(data);
  return data.toString("base64");
}

test("Given optional notification metadata When parsed Then known typed fields survive and nested unknown fields disappear", () => {
  // Given
  const event = notification(1);
  Object.assign(event.payload, { iconPngBase64: PNG, progress: { value: 3, max: 10, indeterminate: false, future: "ignored" },
    isOngoing: true, isRedacted: false, category: "", future: "ignored" });
  // When
  const parsed = parseEvent(ANDROID, event);
  // Then
  assert.equal(parsed.status, 0);
  assert.deepEqual(parsed.event.payload, { ...notification(1).payload, iconPngBase64: PNG,
    progress: { value: 3, max: 10, indeterminate: false }, isOngoing: true, isRedacted: false, category: "" });
});

for (const [field, value, status] of [
  ["iconPngBase64", "AB==", 400], ["iconPngBase64", PNG + "\n", 400], ["iconPngBase64", paddedPng(12_289), 413],
  ["progress", null, 400], ["progress", { value: 1, max: 0, indeterminate: true }, 400],
  ["progress", { value: 0, max: 0, indeterminate: false }, 400],
  ["progress", { value: 2, max: 1, indeterminate: false }, 400],
  ["progress", { value: 0.5, max: 1, indeterminate: false }, 400],
  ["progress", { value: 0, max: 2_147_483_648, indeterminate: true }, 400],
  ["progress", { value: 0, max: 1, indeterminate: "false" }, 400],
  ["isOngoing", null, 400], ["isRedacted", "true", 400], ["category", "한".repeat(43), 413],
]) {
  test(`Given invalid notification ${field} ${JSON.stringify(value).slice(0, 70)} When parsed Then rejected`, () => {
    // Given
    const event = notification(1);
    event.payload[field] = value;
    // When
    const parsed = parseEvent(ANDROID, event);
    // Then
    assert.equal(parsed.status, status);
  });
}

test("Given an icon over128 pixels When parsed Then dimensions are rejected", () => {
  // Given
  const icon = Buffer.from(PNG, "base64"); icon.writeUInt32BE(129, 16);
  const event = notification(1); event.payload.iconPngBase64 = icon.toString("base64");
  // When
  const parsed = parseEvent(ANDROID, event);
  // Then
  assert.equal(parsed.status, 400);
});

test("Given unknown total progress When parsed Then zero maximum is accepted only as indeterminate", () => {
  // Given
  const event = notification(1); event.payload.progress = { value: 0, max: 0, indeterminate: true };
  // When
  const parsed = parseEvent(ANDROID, event);
  // Then
  assert.deepEqual(parsed.event?.payload.progress, event.payload.progress);
});

test("Given metadata pushes a notification over its unchanged cap When parsed Then rejected", () => {
  // Given
  const event = notification(1); Object.assign(event.payload, {
    notificationKey: "k".repeat(4_096), appLabel: "a".repeat(4_096), title: "t".repeat(8_192), body: "b".repeat(65_000),
    iconPngBase64: paddedPng(1_024),
  });
  // When
  const parsed = parseEvent(ANDROID, event);
  // Then
  assert.equal(LIMITS.notificationPayload, 81_920);
  assert.equal(parsed.status, 413);
});

for (const actor of [ANDROID, MACOS]) {
  test(`Given a PNG from ${actor.role} When parsed Then original image bytes and MIME survive`, () => {
    // Given
    const event = imageEvent({ originRole: actor.role, originDeviceId: actor.deviceId });
    event.payload.future = "ignored";
    // When
    const parsed = parseEvent(actor, event);
    // Then
    assert.equal(parsed.status, 0);
    assert.deepEqual(parsed.event.payload, { mimeType: "image/png", dataBase64: PNG });
  });
}

for (const [payload, status] of [
  [{ mimeType: "image/jpeg", dataBase64: "/9j/2Q==" }, 0],
  [{ mimeType: "image/jpeg", dataBase64: PNG }, 400], [{ mimeType: "image/gif", dataBase64: PNG }, 400],
  [{ mimeType: "image/png", dataBase64: "iVBORw0KGgo" }, 400], [{ mimeType: "image/png", dataBase64: PNG + " " }, 400],
  [{ mimeType: "image/png", dataBase64: paddedPng(8_388_608) }, 0],
  [{ mimeType: "image/png", dataBase64: paddedPng(8_388_609) }, 413],
]) {
  test(`Given image type ${payload.mimeType} and encoded length ${payload.dataBase64.length} When parsed Then status ${status}`, () => {
    // Given / When
    const parsed = parseEvent(ANDROID, imageEvent({ payload }));
    // Then
    assert.equal(parsed.status, status);
  });
}

test("Given a valid image above the former request limit When posted and fetched over TLS Then exact bytes are returned", async (t) => {
  // Given
  const server = await startProcess(); t.after(() => stopProcess(server.child));
  const event = imageEvent({ payload: { mimeType: "image/png", dataBase64: paddedPng(1_100_000) } });
  const options = { port: server.port, ca: server.tls.ca };
  // When
  const posted = await request({ ...options, token: server.tokens.android, method: "POST", path: "/v1/events", body: event });
  const fetched = await request({ ...options, token: server.tokens.macos, path: "/v1/events?after=0&waitMs=0" });
  // Then
  assert.equal(posted.status, 201);
  assert.deepEqual(fetched.body.events.map((entry) => entry.event.payload), [event.payload]);
});

test("Given an event request at and above12MiB When posted over TLS Then only the bounded request is accepted", async (t) => {
  // Given
  const server = await startProcess(); t.after(() => stopProcess(server.child));
  const encoded = JSON.stringify(imageEvent());
  const body = encoded + " ".repeat(12_582_912 - Buffer.byteLength(encoded));
  const options = { port: server.port, ca: server.tls.ca, token: server.tokens.android, method: "POST", path: "/v1/events" };
  // When
  const accepted = await request({ ...options, body });
  const rejected = await request({ ...options, body: body + " " });
  // Then
  assert.equal(accepted.status, 201);
  assert.equal(rejected.status, 413);
  assert.equal(rejected.body.error.code, "payload_too_large");
});

test("Given metadata at and above81920 canonical bytes When parsed Then notification admission keeps its original cap", () => {
  // Given
  const event = notification(1);
  Object.assign(event.payload, { notificationKey: "k".repeat(4_096), appLabel: "a".repeat(4_096), title: "t".repeat(8_192),
    iconPngBase64: paddedPng(1_024), body: "" });
  event.payload.body = "b".repeat(81_920 - Buffer.byteLength(JSON.stringify(event.payload)));
  // When
  const accepted = parseEvent(ANDROID, event);
  event.payload.body += "b";
  const rejected = parseEvent(ANDROID, event);
  // Then
  assert.equal(accepted.status, 0);
  assert.equal(rejected.status, 413);
});
