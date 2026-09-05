import { createHash, randomBytes } from "node:crypto";
import { StateError } from "./errors.mjs";
import { parseEvent } from "./schema.mjs";

export const MAX_REPLAY_ORIGIN_KEYS = 64;

export function emptyState() {
  return { schemaVersion: 1, serverEpoch: randomBytes(16).toString("hex"), nextCursor: "1",
    retained: [], highWaters: [], activeEpochs: [], retiredEpochs: [], dedupe: [] };
}

const digestPattern = /^[0-9a-f]{64}$/;

function legacyDigest(entry) {
  try {
    const event = JSON.parse(entry.fingerprint);
    const parsed = parseEvent({ role: event.originRole, deviceId: event.originDeviceId }, event);
    if (parsed.status !== 0 || JSON.stringify(parsed.event) !== entry.fingerprint || event.eventId !== entry.eventId ||
      `${event.originDeviceId}\u0000${event.originEpoch}` !== entry.originKey || event.sequence !== entry.sequence ||
      event.originRole !== entry.originRole) return undefined;
    return fingerprint(parsed.event);
  } catch {
    return undefined;
  }
}

export function prepareState(value) {
  const state = structuredClone(value);
  let migrated = false;
  if (Array.isArray(state?.dedupe)) for (const entry of state.dedupe) {
    if (digestPattern.test(entry?.fingerprint ?? "")) continue;
    const digest = legacyDigest(entry ?? {});
    if (digest === undefined) throw new StateError(new TypeError("invalid dedupe fingerprint"));
    entry.fingerprint = digest;
    migrated = true;
  }
  return { state: validateState(state), migrated };
}

export function validateState(value) {
  const canonicalCursor = (cursor) => typeof cursor === "string" && /^(0|[1-9][0-9]*)$/.test(cursor) && Number.isSafeInteger(Number(cursor));
  const unique = (values) => new Set(values).size === values.length;
  if (value === null || typeof value !== "object" || value.schemaVersion !== 1 ||
      !/^[0-9a-f]{32}$/.test(value.serverEpoch ?? "") || !canonicalCursor(value.nextCursor) || value.nextCursor === "0" ||
      !Array.isArray(value.retained) || !Array.isArray(value.highWaters) || !Array.isArray(value.activeEpochs) ||
      !Array.isArray(value.retiredEpochs) || !Array.isArray(value.dedupe) || value.dedupe.length > 4_096 ||
      value.highWaters.length > MAX_REPLAY_ORIGIN_KEYS || value.activeEpochs.length > MAX_REPLAY_ORIGIN_KEYS ||
      value.retiredEpochs.length > MAX_REPLAY_ORIGIN_KEYS) {
    throw new StateError(new TypeError("invalid state schema"));
  }
  if (value.highWaters.some((entry) => entry === null || typeof entry.key !== "string" ||
      !Number.isSafeInteger(entry.sequence) || entry.sequence < 1) || !unique(value.highWaters.map((entry) => entry.key)) ||
      value.activeEpochs.some((entry) => entry === null || typeof entry.deviceId !== "string" || entry.deviceId.length === 0 ||
        typeof entry.epoch !== "string" || entry.epoch.length === 0) || !unique(value.activeEpochs.map((entry) => entry.deviceId)) ||
      value.retiredEpochs.some((entry) => typeof entry !== "string") || !unique(value.retiredEpochs)) {
    throw new StateError(new TypeError("invalid ordering state"));
  }
  if (value.dedupe.some((entry) => entry === null || typeof entry.eventId !== "string" || entry.eventId.length === 0 ||
      typeof entry.originKey !== "string" || !Number.isSafeInteger(entry.sequence) || entry.sequence < 1 ||
      !digestPattern.test(entry.fingerprint ?? "") || !canonicalCursor(entry.cursor) || !["android", "macos"].includes(entry.originRole)) ||
      !unique(value.dedupe.map((entry) => entry.eventId)) ||
      !unique(value.dedupe.map((entry) => `${entry.originKey}\u0000${entry.sequence}`))) {
    throw new StateError(new TypeError("invalid dedupe state"));
  }
  for (const entry of value.retained) {
    if (!canonicalCursor(entry.cursor) || !["android", "macos"].includes(entry.destination) ||
        !Number.isSafeInteger(entry.bytes) || entry.bytes < 0 || entry.event === null || typeof entry.event !== "object" ||
        (entry.event.kind === "android.notification" ? !Number.isSafeInteger(entry.deadlineMs) : entry.deadlineMs !== null)) {
      throw new StateError(new TypeError("invalid retained state"));
    }
    const parsed = parseEvent({ role: entry.event.originRole, deviceId: entry.event.originDeviceId }, entry.event);
    const dedupe = value.dedupe.find((candidate) => candidate.eventId === entry.event.eventId);
    if (parsed.status !== 0 || dedupe === undefined || fingerprint(parsed.event) !== dedupe.fingerprint ||
        destinationFor(entry.event) !== entry.destination ||
        Buffer.byteLength(JSON.stringify(entry.event), "utf8") !== entry.bytes) throw new StateError(new TypeError("invalid retained event"));
  }
  if (!unique(value.retained.map((entry) => entry.cursor)) ||
      [...value.retained, ...value.dedupe].some((entry) => Number(entry.cursor) >= Number(value.nextCursor))) {
    throw new StateError(new TypeError("invalid cursor state"));
  }
  return structuredClone(value);
}

export function destinationFor(event) {
  return event.originRole === "android" ? "macos" : "android";
}

export function fingerprint(event) {
  return createHash("sha256").update(JSON.stringify(event), "utf8").digest("hex");
}
