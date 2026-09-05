import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import test from "node:test";
import { parseEvent, parseAck } from "../src/schema.mjs";
import { Authenticator } from "../src/auth.mjs";
import { ANDROID, MACOS, clipboard, notification } from "./helpers.mjs";

test("Given malformed event variants When parsed Then stable protocol errors and UTF-8 limits apply", () => {
  // Given
  const base = clipboard();
  const cases = [
    [{ ...base, protocolVersion: 2 }, [400, "unsupported_protocol_version"]],
    [{ ...base, kind: "future.kind" }, [400, "unsupported_kind"]],
    [{ ...base, sequence: "1" }, [400, "invalid_event"]],
    [{ ...base, payload: { text: "😀".repeat(262_145) } }, [413, "payload_too_large"]],
    [{ ...base, originRole: "macos", originDeviceId: MACOS.deviceId, kind: "android.notification", payload: {
      notificationKey: "k", packageName: "p", appLabel: "a", title: "t", body: "b",
    } }, [403, "direction_forbidden"]],
  ];
  // When / Then
  for (const [value, expected] of cases) {
    const result = parseEvent(MACOS.role === value.originRole ? MACOS : ANDROID, value);
    assert.deepEqual([result.status, result.code], expected);
  }
});

test("Given notification UTF-8 field overflow When parsed Then payload is rejected", () => {
  // Given
  const event = notification(1, { payload: {
    notificationKey: "key", packageName: "com.example.harmless", appLabel: "Harmless", title: "", body: "😀".repeat(16_385),
  } });
  // When
  const result = parseEvent(ANDROID, event);
  // Then
  assert.deepEqual([result.status, result.code], [413, "payload_too_large"]);
});

test("Given acknowledgement field errors When parsed Then invalid ack is returned", () => {
  // Given / When
  const wrongIdentity = parseAck(MACOS, { protocolVersion: 1, recipientDeviceId: "other", recipientRole: "macos", eventIds: ["x"] });
  const tooMany = parseAck(MACOS, { protocolVersion: 1, recipientDeviceId: MACOS.deviceId, recipientRole: "macos", eventIds: Array(101).fill("x") });
  const unknownVersion = parseAck(MACOS, { protocolVersion: 2, recipientDeviceId: MACOS.deviceId, recipientRole: "macos", eventIds: ["x"] });
  // Then
  assert.deepEqual([wrongIdentity.status, wrongIdentity.code], [403, "identity_mismatch"]);
  assert.deepEqual([tooMany.status, tooMany.code], [400, "invalid_ack"]);
  assert.deepEqual([unknownVersion.status, unknownVersion.code], [400, "unsupported_protocol_version"]);
});

test("Given fixed role credentials When token is missing bad or revoked Then auth fails closed", () => {
  // Given
  const tokens = { android: randomBytes(32).toString("hex"), revoked: randomBytes(32).toString("hex") };
  const auth = new Authenticator([
    { token: tokens.android, ...ANDROID, revoked: false },
    { token: tokens.revoked, ...MACOS, revoked: true },
  ]);
  // When
  const missing = auth.authenticate(undefined);
  const bad = auth.authenticate("Bearer wrong");
  const revoked = auth.authenticate(`Bearer ${tokens.revoked}`);
  const good = auth.authenticate(`Bearer ${tokens.android}`);
  // Then
  assert.deepEqual([missing.code, bad.code, revoked.code], ["unauthorized", "unauthorized", "unauthorized"]);
  assert.deepEqual(good, ANDROID);
});
