const bytes = (value) => Buffer.byteLength(value, "utf8");
const invalid = () => ({ status: 400, code: "invalid_event" });
const oversized = () => ({ status: 413, code: "payload_too_large" });
const PNG = Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]);

export function knownPayload(kind, payload) {
  switch (kind) {
    case "clipboard.text": return { text: payload.text };
    case "clipboard.image": return { mimeType: payload.mimeType, dataBase64: payload.dataBase64 };
    case "android.notification": {
      const result = {};
      for (const field of ["notificationKey", "packageName", "appLabel", "title", "body", "iconPngBase64", "isOngoing", "isRedacted", "category"]) {
        if (Object.hasOwn(payload, field)) result[field] = payload[field];
      }
      if (Object.hasOwn(payload, "progress")) result.progress = {
        value: payload.progress.value, max: payload.progress.max, indeterminate: payload.progress.indeterminate,
      };
      return result;
    }
    default: throw new TypeError("unknown payload");
  }
}

function base64(value, limits) {
  if (typeof value !== "string") return invalid();
  if (bytes(value) > limits.encoded) return oversized();
  const data = Buffer.from(value, "base64");
  if (data.length > limits.decoded) return oversized();
  if (data.toString("base64") !== value) return invalid();
  return { status: 0, data };
}

export function payloadStatus(kind, payload) {
  switch (kind) {
    case "clipboard.text":
      if (typeof payload.text !== "string") return invalid();
      return bytes(payload.text) > 1_048_576 ? oversized() : { status: 0 };
    case "clipboard.image": {
      if (payload.mimeType !== "image/png" && payload.mimeType !== "image/jpeg") return invalid();
      const result = base64(payload.dataBase64, { encoded: 11_184_812, decoded: 8_388_608 });
      if (result.status !== 0) return result;
      const signature = payload.mimeType === "image/png" ? PNG : Buffer.from([255, 216, 255]);
      return result.data.subarray(0, signature.length).equals(signature) ? { status: 0 } : invalid();
    }
    case "android.notification": return notificationStatus(payload);
    default: return invalid();
  }
}

function notificationStatus(payload) {
  for (const [field, cap] of [["notificationKey", 4096], ["packageName", 255], ["appLabel", 4096], ["title", 8192], ["body", 65536]]) {
    if (typeof payload[field] !== "string") return invalid();
    if (bytes(payload[field]) > cap) return oversized();
  }
  for (const field of ["isOngoing", "isRedacted"]) {
    if (Object.hasOwn(payload, field) && typeof payload[field] !== "boolean") return invalid();
  }
  if (Object.hasOwn(payload, "category")) {
    if (typeof payload.category !== "string") return invalid();
    if (bytes(payload.category) > 128) return oversized();
  }
  if (Object.hasOwn(payload, "progress")) {
    const p = payload.progress;
    if (p === null || typeof p !== "object" || Array.isArray(p) || typeof p.indeterminate !== "boolean" ||
      !Number.isInteger(p.value) || !Number.isInteger(p.max) || p.value < 0 || p.max < 0 ||
      p.value > p.max || p.max > 2_147_483_647 || (p.max === 0 && !p.indeterminate)) return invalid();
  }
  if (Object.hasOwn(payload, "iconPngBase64")) {
    const result = base64(payload.iconPngBase64, { encoded: 16_384, decoded: 12_288 });
    if (result.status !== 0) return result;
    const data = result.data;
    if (data.length < 33 || !data.subarray(0, 8).equals(PNG) || data.readUInt32BE(8) !== 13 ||
      data.toString("ascii", 12, 16) !== "IHDR" || data.readUInt32BE(16) < 1 || data.readUInt32BE(16) > 128 ||
      data.readUInt32BE(20) < 1 || data.readUInt32BE(20) > 128) return invalid();
  }
  return bytes(JSON.stringify(knownPayload("android.notification", payload))) > 81_920 ? oversized() : { status: 0 };
}
