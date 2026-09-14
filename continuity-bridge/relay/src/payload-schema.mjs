import { failure } from "./errors.mjs";

export const PAYLOAD_LIMITS = Object.freeze({ clipboard: 1_048_576, imageDecoded: 8_388_608, imageEncoded: 11_184_812,
  iconDecoded: 12_288, iconEncoded: 16_384, notificationPayload: 81_920 });
const PNG_SIGNATURE = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);
const bytes = (value) => Buffer.byteLength(value, "utf8");
const object = (value) => value !== null && typeof value === "object" && !Array.isArray(value);

function decodeBase64(value, encodedLimit, decodedLimit) {
  if (typeof value !== "string") return failure("invalid_event");
  if (value.length > encodedLimit || bytes(value) > encodedLimit) return failure("payload_too_large");
  const data = Buffer.from(value, "base64");
  if (data.length > decodedLimit) return failure("payload_too_large");
  if (data.toString("base64") !== value) return failure("invalid_event");
  return { status: 0, data };
}

function parseImage(payload) {
  if (!["image/png", "image/jpeg"].includes(payload.mimeType)) return failure("invalid_event");
  const decoded = decodeBase64(payload.dataBase64, PAYLOAD_LIMITS.imageEncoded, PAYLOAD_LIMITS.imageDecoded);
  if (decoded.status !== 0) return decoded;
  const signature = payload.mimeType === "image/png" ? PNG_SIGNATURE : Buffer.from([255, 216, 255]);
  if (!decoded.data.subarray(0, signature.length).equals(signature)) return failure("invalid_event");
  return { status: 0 };
}

function parseIcon(value) {
  const decoded = decodeBase64(value, PAYLOAD_LIMITS.iconEncoded, PAYLOAD_LIMITS.iconDecoded);
  if (decoded.status !== 0) return decoded;
  const data = decoded.data;
  if (data.length < 33 || !data.subarray(0, 8).equals(PNG_SIGNATURE) || data.readUInt32BE(8) !== 13 ||
      data.toString("ascii", 12, 16) !== "IHDR" || data.readUInt32BE(16) < 1 || data.readUInt32BE(16) > 128 ||
      data.readUInt32BE(20) < 1 || data.readUInt32BE(20) > 128) return failure("invalid_event");
  return { status: 0 };
}

function parseNotification(payload) {
  const fields = [["notificationKey", 4_096], ["packageName", 255], ["appLabel", 4_096], ["title", 8_192], ["body", 65_536]];
  for (const [field, limit] of fields) {
    if (typeof payload[field] !== "string") return failure("invalid_event");
    if (bytes(payload[field]) > limit) return failure("payload_too_large");
  }
  if (Object.hasOwn(payload, "iconPngBase64")) {
    const result = parseIcon(payload.iconPngBase64);
    if (result.status !== 0) return result;
  }
  if (Object.hasOwn(payload, "progress")) {
    const p = payload.progress;
    if (!object(p) || !Number.isInteger(p.value) || !Number.isInteger(p.max) || p.value < 0 || p.max < 0 ||
        p.max > 2_147_483_647 || p.value > p.max || typeof p.indeterminate !== "boolean" ||
        (p.max === 0 && !p.indeterminate)) return failure("invalid_event");
  }
  for (const field of ["isOngoing", "isRedacted"]) {
    if (Object.hasOwn(payload, field) && typeof payload[field] !== "boolean") return failure("invalid_event");
  }
  if (Object.hasOwn(payload, "category")) {
    if (typeof payload.category !== "string") return failure("invalid_event");
    if (bytes(payload.category) > 128) return failure("payload_too_large");
  }
  if (bytes(JSON.stringify(normalizedPayload("android.notification", payload))) > PAYLOAD_LIMITS.notificationPayload) {
    return failure("payload_too_large");
  }
  return { status: 0 };
}

export function normalizedPayload(kind, payload) {
  switch (kind) {
    case "clipboard.text": return { text: payload.text };
    case "clipboard.image": return { mimeType: payload.mimeType, dataBase64: payload.dataBase64 };
    case "android.notification": {
      const normalized = { notificationKey: payload.notificationKey, packageName: payload.packageName,
        appLabel: payload.appLabel, title: payload.title, body: payload.body };
      for (const field of ["iconPngBase64", "isOngoing", "isRedacted", "category"]) {
        if (Object.hasOwn(payload, field)) normalized[field] = payload[field];
      }
      if (Object.hasOwn(payload, "progress")) {
        const { value, max, indeterminate } = payload.progress;
        normalized.progress = { value, max, indeterminate };
      }
      return normalized;
    }
    default: throw new TypeError("unsupported payload kind");
  }
}

export function parsePayload(kind, payload) {
  switch (kind) {
    case "clipboard.text":
      if (typeof payload.text !== "string") return failure("invalid_event");
      return bytes(payload.text) > PAYLOAD_LIMITS.clipboard ? failure("payload_too_large") : { status: 0 };
    case "clipboard.image": return parseImage(payload);
    case "android.notification": return parseNotification(payload);
    default: return failure("unsupported_kind");
  }
}
