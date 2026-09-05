import { failure } from "./errors.mjs";

export const LIMITS = Object.freeze({ eventBody: 1_114_112, ackBody: 65_536, clipboard: 1_048_576,
  notificationPayload: 81_920, notificationBytes: 524_288, notificationCount: 100, notificationTtlMs: 900_000 });
const ROLES = new Set(["android", "macos"]);
const KINDS = new Set(["clipboard.text", "android.notification"]);
const bytes = (value) => Buffer.byteLength(value, "utf8");
const isObject = (value) => value !== null && typeof value === "object" && !Array.isArray(value);
const validString = (value, limit, empty = false) => typeof value === "string" && (empty || value.length > 0) && bytes(value) <= limit;
const safeInteger = (value, minimum) => Number.isSafeInteger(value) && value >= minimum;

function normalizedPayload(kind, payload) {
  if (kind === "clipboard.text") return { text: payload.text };
  return { notificationKey: payload.notificationKey, packageName: payload.packageName,
    appLabel: payload.appLabel, title: payload.title, body: payload.body };
}

export function normalizeEvent(event) {
  const normalized = { protocolVersion: event.protocolVersion, eventId: event.eventId,
    originDeviceId: event.originDeviceId, originRole: event.originRole, originEpoch: event.originEpoch,
    sequence: event.sequence, kind: event.kind, createdAtMs: event.createdAtMs };
  if (event.expiresAtMs !== undefined) normalized.expiresAtMs = event.expiresAtMs;
  normalized.payload = normalizedPayload(event.kind, event.payload);
  return normalized;
}

export function parseEvent(actor, event) {
  if (!isObject(event)) return failure("invalid_event");
  if (event.protocolVersion !== 1) return failure("unsupported_protocol_version");
  if (typeof event.kind !== "string" || !KINDS.has(event.kind)) return failure("unsupported_kind");
  if (!validString(event.eventId, 128) || !validString(event.originDeviceId, 128) ||
      !validString(event.originEpoch, 128) || !ROLES.has(event.originRole) ||
      !safeInteger(event.sequence, 1) || !safeInteger(event.createdAtMs, 0) || !isObject(event.payload)) {
    return failure("invalid_event");
  }
  if (event.expiresAtMs !== undefined && (!safeInteger(event.expiresAtMs, 0) || event.expiresAtMs < event.createdAtMs)) {
    return failure("invalid_event");
  }
  if (actor.role !== event.originRole || actor.deviceId !== event.originDeviceId) return failure("identity_mismatch");
  if (event.originRole === "macos" && event.kind !== "clipboard.text") return failure("direction_forbidden");
  if (event.kind === "clipboard.text") {
    if (typeof event.payload.text !== "string") return failure("invalid_event");
    if (bytes(event.payload.text) > LIMITS.clipboard) return failure("payload_too_large");
  } else {
    const fields = [["notificationKey", 4_096], ["packageName", 255], ["appLabel", 4_096], ["title", 8_192], ["body", 65_536]];
    for (const [field, limit] of fields) {
      if (!validString(event.payload[field], limit, true)) return typeof event.payload[field] === "string"
        ? failure("payload_too_large") : failure("invalid_event");
    }
    if (bytes(JSON.stringify(normalizedPayload(event.kind, event.payload))) > LIMITS.notificationPayload) return failure("payload_too_large");
  }
  return { status: 0, event: normalizeEvent(event) };
}

export function parseAck(actor, value) {
  if (!isObject(value)) return failure("invalid_ack");
  if (value.protocolVersion !== 1) return failure("unsupported_protocol_version");
  if (!Array.isArray(value.eventIds) ||
      value.eventIds.length < 1 || value.eventIds.length > 100 ||
      value.eventIds.some((id) => !validString(id, 128)) || new Set(value.eventIds).size !== value.eventIds.length) {
    return failure("invalid_ack");
  }
  if (value.recipientRole !== actor.role || value.recipientDeviceId !== actor.deviceId) return failure("identity_mismatch");
  return { status: 0, eventIds: [...value.eventIds] };
}

export function parseQuery(searchParams) {
  if ([...searchParams.keys()].some((key) => key !== "after" && key !== "waitMs") ||
      searchParams.getAll("after").length !== 1 || searchParams.getAll("waitMs").length !== 1) return failure("invalid_query");
  const after = searchParams.get("after");
  const waitText = searchParams.get("waitMs");
  if (!/^(0|[1-9][0-9]*)$/.test(after ?? "") || !/^(0|[1-9][0-9]*)$/.test(waitText ?? "")) return failure("invalid_query");
  const waitMs = Number(waitText);
  if (!Number.isSafeInteger(Number(after)) || !Number.isSafeInteger(waitMs) || waitMs > 25_000) return failure("invalid_query");
  return { status: 0, after, waitMs };
}
