import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { knownPayload, payloadStatus } from "./contract-payloads.mjs";

const LIMITS = Object.freeze({
  clipboardBytes: 1_048_576,
  eventBodyBytes: 12_582_912,
  responseBodyBytes: 12_582_912,
  notificationPayloadBytes: 81_920,
  notificationRetainedBytes: 524_288,
  notificationCount: 100,
  notificationTtlMs: 900_000,
});
const ROLES = new Set(["android", "macos"]);
const KINDS = new Set(["clipboard.text", "clipboard.image", "android.notification"]);
const isClipboard = (kind) => kind === "clipboard.text" || kind === "clipboard.image";
const REQUIRED_CAPABILITIES = Object.freeze([
  "role_acl_android_publish_clipboard",
  "role_acl_android_publish_notification",
  "role_acl_macos_publish_clipboard",
  "role_acl_macos_notification_forbidden",
  "role_identity_mismatch",
  "unknown_role_rejected",
  "unknown_protocol_version",
  "unknown_event_kind",
  "malformed_json",
  "wrong_type_field",
  "missing_required_field",
  "oversized_utf8",
  "stale_sequence",
  "idempotent_retry_original_cursor",
  "event_id_conflict_409",
  "sequence_conflict_409",
  "clipboard_supersession",
  "origin_epoch_retirement",
  "notification_per_event_retention",
  "notification_ttl",
  "notification_count_bound",
  "notification_byte_bound",
  "cursor_replay",
  "server_epoch_restart",
  "ack_timing",
  "unknown_field_opacity",
  "no_wall_clock_ordering",
  "image_bidirectional",
  "clipboard_cross_kind_supersession",
  "image_signature",
  "notification_metadata",
  "notification_progress_validation",
  "notification_metadata_normalization",
]);
const REQUIRED_CAPABILITY_SET = new Set(REQUIRED_CAPABILITIES);
const utf8Bytes = (value) => Buffer.byteLength(value, "utf8");
const clone = (value) => structuredClone(value);

function error(status, code) {
  return { status, code };
}

function knownEvent(event) {
  const envelope = {
    protocolVersion: event.protocolVersion,
    eventId: event.eventId,
    originDeviceId: event.originDeviceId,
    originRole: event.originRole,
    originEpoch: event.originEpoch,
    sequence: event.sequence,
    kind: event.kind,
    createdAtMs: event.createdAtMs,
  };
  if (event.expiresAtMs !== undefined) envelope.expiresAtMs = event.expiresAtMs;
  envelope.payload = knownPayload(event.kind, event.payload);
  return envelope;
}

function validateEvent(actorRole, event) {
  if (!ROLES.has(actorRole)) return error(401, "unauthorized");
  if (event === null || Array.isArray(event) || typeof event !== "object") return error(400, "invalid_event");
  if (utf8Bytes(JSON.stringify(event)) > LIMITS.eventBodyBytes) return error(413, "payload_too_large");
  if (event.protocolVersion !== 1) return error(400, "unsupported_protocol_version");
  if (typeof event.kind !== "string" || !KINDS.has(event.kind)) return error(400, "unsupported_kind");
  for (const [field, limit] of [["eventId", 128], ["originDeviceId", 128], ["originEpoch", 128]]) {
    if (typeof event[field] !== "string" || event[field].length === 0 || utf8Bytes(event[field]) > limit) {
      return error(400, "invalid_event");
    }
  }
  if (!ROLES.has(event.originRole)) return error(400, "invalid_event");
  for (const field of ["sequence", "createdAtMs"]) {
    if (!Number.isSafeInteger(event[field]) || event[field] < (field === "sequence" ? 1 : 0)) {
      return error(400, "invalid_event");
    }
  }
  if (event.expiresAtMs !== undefined &&
      (!Number.isSafeInteger(event.expiresAtMs) || event.expiresAtMs < event.createdAtMs)) {
    return error(400, "invalid_event");
  }
  if (actorRole !== event.originRole) return error(403, "identity_mismatch");
  if (actorRole === "macos" && !isClipboard(event.kind)) return error(403, "direction_forbidden");
  if (event.payload === null || Array.isArray(event.payload) || typeof event.payload !== "object") {
    return error(400, "invalid_event");
  }
  const payload = payloadStatus(event.kind, event.payload);
  if (payload.status !== 0) return payload;
  return { status: 0, event: knownEvent(event) };
}

class ContractStore {
  constructor() {
    this.nowMs = 1_700_000_000_000;
    this.serverEpoch = "server-epoch-fixture-001";
    this.tailCursor = 0;
    this.byEventId = new Map();
    this.bySequence = new Map();
    this.highWater = new Map();
    this.activeEpoch = new Map();
    this.retiredEpochs = new Set();
    this.retained = new Map();
  }

  expireAndBound() {
    for (const [cursor, record] of this.retained) {
      if (record.event.kind === "android.notification" && record.deadlineMs <= this.nowMs) this.retained.delete(cursor);
    }
    const notifications = () => [...this.retained.entries()]
      .filter(([, record]) => record.event.kind === "android.notification")
      .sort((left, right) => Number(left[0]) - Number(right[0]));
    const totalBytes = (entries) => entries.reduce((sum, [, record]) => sum + record.bytes, 0);
    let entries = notifications();
    while (entries.length > LIMITS.notificationCount || totalBytes(entries) > LIMITS.notificationRetainedBytes) {
      this.retained.delete(entries[0][0]);
      entries = notifications();
    }
  }

  publish(actorRole, rawEvent) {
    this.expireAndBound();
    const validation = validateEvent(actorRole, rawEvent);
    if (validation.status !== 0) return validation;
    const event = validation.event;
    const fingerprint = JSON.stringify(event);
    const priorId = this.byEventId.get(event.eventId);
    if (priorId) {
      if (priorId.fingerprint === fingerprint) return { status: 200, cursor: priorId.cursor, idempotent: true };
      return error(409, "event_id_conflict");
    }
    const originKey = `${event.originDeviceId}\u0000${event.originEpoch}`;
    const sequenceKey = `${originKey}\u0000${event.sequence}`;
    const priorSequence = this.bySequence.get(sequenceKey);
    if (priorSequence) {
      if (priorSequence.fingerprint === fingerprint) return { status: 200, cursor: priorSequence.cursor, idempotent: true };
      return error(409, "sequence_conflict");
    }
    const highWater = this.highWater.get(originKey) ?? 0;
    if (event.sequence <= highWater) return error(409, "stale_sequence");
    const activeEpoch = this.activeEpoch.get(event.originDeviceId);
    if (this.retiredEpochs.has(originKey)) return error(409, "stale_sequence");
    if (activeEpoch !== undefined && activeEpoch !== event.originEpoch && event.sequence !== 1) {
      return error(409, "stale_sequence");
    }
    if (activeEpoch !== undefined && activeEpoch !== event.originEpoch) {
      this.retiredEpochs.add(`${event.originDeviceId}\u0000${activeEpoch}`);
    }
    this.activeEpoch.set(event.originDeviceId, event.originEpoch);
    this.tailCursor += 1;
    const cursor = String(this.tailCursor);
    const identity = { cursor, fingerprint };
    this.byEventId.set(event.eventId, identity);
    this.bySequence.set(sequenceKey, identity);
    this.highWater.set(originKey, event.sequence);
    const destination = event.originRole === "android" ? "macos" : "android";
    if (isClipboard(event.kind)) {
      for (const [oldCursor, record] of this.retained) {
        if (record.event.originDeviceId === event.originDeviceId && isClipboard(record.event.kind)) this.retained.delete(oldCursor);
      }
    }
    const requestedDeadline = event.expiresAtMs ?? Number.MAX_SAFE_INTEGER;
    this.retained.set(cursor, {
      event,
      destination,
      deadlineMs: event.kind === "android.notification" ? Math.min(this.nowMs + LIMITS.notificationTtlMs, requestedDeadline) : null,
      bytes: utf8Bytes(JSON.stringify(event)),
    });
    this.expireAndBound();
    return { status: 201, cursor, idempotent: false };
  }

  fetch(actorRole, after, waitMs) {
    this.expireAndBound();
    if (!ROLES.has(actorRole) || !/^(0|[1-9][0-9]*)$/.test(after) ||
        !Number.isSafeInteger(waitMs) || waitMs < 0 || waitMs > 25_000) return error(400, "invalid_query");
    const afterNumber = Number(after);
    const records = [...this.retained.entries()]
      .filter(([cursor, record]) => record.destination === actorRole && Number(cursor) > afterNumber)
      .sort((left, right) => Number(left[0]) - Number(right[0]));
    let nextCursor = String(this.tailCursor);
    let length = utf8Bytes(JSON.stringify({ status: 200, protocolVersion: 1, serverEpoch: this.serverEpoch,
      after, nextCursor, events: [] }));
    const selected = [];
    for (const [cursor, record] of records) {
      const entryBytes = utf8Bytes(JSON.stringify({ cursor, ...record })) + (selected.length ? 1 : 0);
      if (length + entryBytes > LIMITS.responseBodyBytes) {
        nextCursor = selected.at(-1)?.[0] ?? after;
        break;
      }
      selected.push([cursor, record]); length += entryBytes;
    }
    return {
      status: 200,
      eventIds: selected.map(([, record]) => record.event.eventId),
      nextCursor,
      payloadText: selected[0]?.[1].event.payload.text,
      payload: selected[0]?.[1].event.payload,
    };
  }

  ack(actorRole, eventIds) {
    const acked = [];
    const alreadyAbsent = [];
    for (const eventId of eventIds) {
      const match = [...this.retained.entries()].find(([, record]) => record.event.eventId === eventId);
      if (!match) alreadyAbsent.push(eventId);
      else if (match[1].destination !== actorRole) return error(403, "identity_mismatch");
      else {
        this.retained.delete(match[0]);
        acked.push(eventId);
      }
    }
    return { status: 200, acked, alreadyAbsent };
  }

  restart() {
    const snapshot = JSON.parse(JSON.stringify({
      nowMs: this.nowMs,
      serverEpoch: this.serverEpoch,
      tailCursor: this.tailCursor,
      byEventId: [...this.byEventId],
      bySequence: [...this.bySequence],
      highWater: [...this.highWater],
      activeEpoch: [...this.activeEpoch],
      retiredEpochs: [...this.retiredEpochs],
      retained: [...this.retained],
    }));
    this.nowMs = snapshot.nowMs;
    this.serverEpoch = snapshot.serverEpoch;
    this.tailCursor = snapshot.tailCursor;
    this.byEventId = new Map(snapshot.byEventId);
    this.bySequence = new Map(snapshot.bySequence);
    this.highWater = new Map(snapshot.highWater);
    this.activeEpoch = new Map(snapshot.activeEpoch);
    this.retiredEpochs = new Set(snapshot.retiredEpochs);
    this.retained = new Map(snapshot.retained);
    return { status: 0, serverEpochUnchanged: this.serverEpoch === snapshot.serverEpoch, tailCursor: String(this.tailCursor) };
  }
}

function setPath(target, path, value) {
  const parts = path.split(".");
  let owner = target;
  for (const part of parts.slice(0, -1)) owner = owner[part];
  owner[parts.at(-1)] = value;
}

function deletePath(target, path) {
  const parts = path.split(".");
  let owner = target;
  for (const part of parts.slice(0, -1)) owner = owner[part];
  delete owner[parts.at(-1)];
}

async function materializeEvent(step, fixtureRoot) {
  const event = step.event !== undefined
    ? clone(step.event)
    : JSON.parse(await readFile(resolve(fixtureRoot, step.fixture), "utf8"));
  for (const [path, value] of Object.entries(step.set ?? {})) setPath(event, path, value);
  for (const path of step.delete ?? []) deletePath(event, path);
  if (step.repeat) setPath(event, step.repeat.path, step.repeat.value.repeat(step.repeat.count));
  return event;
}

function checkExpected(actual, expected, label) {
  for (const [key, value] of Object.entries(expected)) {
    if (key.endsWith("AtLeast")) {
      assert.ok(actual[key] >= value, `${label}: expected ${key}>=${value}, got ${actual[key]}`);
      continue;
    }
    if (key.endsWith("AtMost")) {
      assert.ok(actual[key] <= value, `${label}: expected ${key}<=${value}, got ${actual[key]}`);
      continue;
    }
    assert.deepEqual(actual[key], value, `${label}: expected ${key}=${JSON.stringify(value)}, got ${JSON.stringify(actual[key])}`);
  }
}

function assertCapabilityCoverage(suite) {
  assert.ok(Array.isArray(suite.scenarios) && suite.scenarios.length > 0, "fixtures must contain scenarios");
  const declared = new Set();
  for (const scenario of suite.scenarios) {
    assert.ok(Array.isArray(scenario.capabilities), `${scenario.name ?? "unnamed scenario"}: capabilities array is required`);
    for (const capability of scenario.capabilities) {
      assert.equal(typeof capability, "string", `${scenario.name}: capability IDs must be strings`);
      assert.ok(REQUIRED_CAPABILITY_SET.has(capability), `${scenario.name}: unknown contract capability: ${capability}`);
      assert.ok(!declared.has(capability), `${scenario.name}: duplicate contract capability: ${capability}`);
      declared.add(capability);
    }
  }
  const missing = REQUIRED_CAPABILITIES.filter((capability) => !declared.has(capability));
  assert.deepEqual(missing, [], `missing required capabilities: ${missing.join(", ")}`);
}

async function executeStep(store, step, fixtureRoot) {
  switch (step.op) {
    case "publish": return store.publish(step.actorRole, await materializeEvent(step, fixtureRoot));
    case "publishRawFile": {
      try {
        return store.publish(step.actorRole, JSON.parse(await readFile(resolve(fixtureRoot, step.fixture), "utf8")));
      } catch (caught) {
        if (caught instanceof SyntaxError) return error(400, "malformed_json");
        throw caught;
      }
    }
    case "fetch": return store.fetch(step.actorRole, step.after, step.waitMs);
    case "applyAndMaybeAck": {
      if (!step.surfaceSucceeded || !step.appliedIdPersisted) return { status: 0, ackWithheld: true };
      return store.ack(step.actorRole, step.eventIds);
    }
    case "advanceMs":
      store.nowMs += step.milliseconds;
      return { status: 0 };
    case "restart": return store.restart();
    case "publishNotificationSeries": {
      let last = { status: 0 };
      for (let index = 1; index <= step.count; index += 1) {
        last = store.publish("android", {
          protocolVersion: 1,
          eventId: `series-note-${String(index).padStart(3, "0")}`,
          originDeviceId: "device-android-series",
          originRole: "android",
          originEpoch: "epoch-android-series-a",
          sequence: index,
          kind: "android.notification",
          createdAtMs: store.nowMs,
          payload: {
            notificationKey: `series-key-${index}`,
            packageName: "com.example.harmlessfixture",
            appLabel: "Harmless Fixture",
            title: `Series ${index}`,
            body: "N".repeat(step.bodyBytes ?? 16),
          },
        });
        assert.equal(last.status, 201, `series publish ${index} must be accepted`);
      }
      const retained = [...store.retained.values()].filter((record) => record.event.kind === "android.notification");
      const retainedBytes = retained.reduce((sum, record) => sum + record.bytes, 0);
      return {
        status: last.status,
        cursor: String(store.tailCursor),
        retainedNotifications: retained.length,
        firstRetainedEventId: retained[0]?.event.eventId,
        retainedNotificationBytesAtMost: retainedBytes,
        evictedAtLeast: step.count - retained.length,
      };
    }
    default: throw new Error(`unknown fixture operation: ${step.op}`);
  }
}

const defaultSuite = fileURLToPath(new URL("./fixtures/contract-cases.json", import.meta.url));
const args = process.argv.slice(2);
if (args[0] === "--conflict") {
  assert.equal(args.length, 3, "usage: --conflict ORIGINAL_EVENT MUTATED_EVENT");
  const original = JSON.parse(await readFile(resolve(args[1]), "utf8"));
  const mutated = JSON.parse(await readFile(resolve(args[2]), "utf8"));
  const store = new ContractStore();
  assert.equal(store.publish(original.originRole, original).status, 201, "original fixture must be accepted");
  const conflict = store.publish(mutated.originRole, mutated);
  assert.equal(conflict.status, 409, "mutated sequence/content reuse must conflict");
  assert.equal(conflict.code, "sequence_conflict", "new event ID with reused sequence/content must be a sequence conflict");
  console.log("CONTRACT_CONFLICT_OK httpStatus=409 code=sequence_conflict");
  process.exit(0);
}
if (args[0] === "--event") {
  assert.equal(args.length, 4, "usage: --event EVENT ACTOR_ROLE EXPECTED_STATUS");
  const event = JSON.parse(await readFile(resolve(args[1]), "utf8"));
  const actual = new ContractStore().publish(args[2], event);
  assert.equal(actual.status, Number(args[3]), `expected HTTP-equivalent status ${args[3]}, got ${actual.status}`);
  console.log(`CONTRACT_EVENT_OK httpStatus=${actual.status}`);
  process.exit(0);
}
if (args[0] === "--negative-control-capabilities") {
  assert.equal(args.length, 1, "usage: --negative-control-capabilities");
  const canonicalSuite = JSON.parse(await readFile(defaultSuite, "utf8"));
  assertCapabilityCoverage(canonicalSuite);
  for (const capability of REQUIRED_CAPABILITIES) {
    const incompleteSuite = clone(canonicalSuite);
    for (const scenario of incompleteSuite.scenarios) {
      scenario.capabilities = scenario.capabilities.filter((candidate) => candidate !== capability);
    }
    assert.throws(
      () => assertCapabilityCoverage(incompleteSuite),
      (caught) => caught instanceof assert.AssertionError && caught.message.includes(capability),
      `removing ${capability} must be rejected with its missing capability ID`,
    );
    console.log(`CAPABILITY_REMOVAL_REJECTED capability=${capability}`);
  }
  console.log(`CONTRACT_CAPABILITY_NEGATIVE_CONTROL_OK count=${REQUIRED_CAPABILITIES.length}`);
  process.exit(0);
}

const suitePath = resolve(args[0] ?? defaultSuite);
const fixtureRoot = dirname(suitePath);
const suite = JSON.parse(await readFile(suitePath, "utf8"));
assert.equal(suite.schemaVersion, 1, "fixture schemaVersion must be 1");
assertCapabilityCoverage(suite);

let assertionCount = 0;
for (const scenario of suite.scenarios) {
  assert.equal(typeof scenario.name, "string", "scenario name is required");
  assert.ok(Array.isArray(scenario.steps) && scenario.steps.length > 0, `${scenario.name}: steps are required`);
  const store = new ContractStore();
  for (const [index, step] of scenario.steps.entries()) {
    const label = `${scenario.name} step ${index + 1} (${step.op})`;
    assert.ok(Number.isInteger(step.expect?.status), `${label}: expected status is required`);
    if ((step.op === "publish" || step.op === "publishNotificationSeries") && [200, 201].includes(step.expect.status)) {
      assert.equal(typeof step.expect.cursor, "string", `${label}: accepted publish must declare cursor`);
    }
    if (step.op === "fetch" && step.expect.status === 200) {
      assert.equal(typeof step.expect.nextCursor, "string", `${label}: successful fetch must declare nextCursor`);
    }
    if (step.expect.status >= 400) assert.equal(typeof step.expect.code, "string", `${label}: rejection must declare code`);
    checkExpected(await executeStep(store, step, fixtureRoot), step.expect, label);
    assertionCount += Object.keys(step.expect).length;
  }
}

console.log(`CONTRACT_FIXTURES_OK count=${assertionCount} scenarios=${suite.scenarios.length}`);
