import { atomicWrite, loadState } from "./persistence.mjs";
import { LIMITS, parseEvent } from "./schema.mjs";
import { destinationFor, emptyState, fingerprint, MAX_REPLAY_ORIGIN_KEYS, prepareState } from "./state-model.mjs";
import { failure } from "./errors.mjs";

const originKey = (event) => `${event.originDeviceId}\u0000${event.originEpoch}`;
const tailCursor = (state) => String(Number(state.nextCursor) - 1);

export class DurableStore {
  constructor(statePath, clock, state) {
    this.statePath = statePath;
    this.clock = clock;
    this.state = state;
    this.queue = Promise.resolve();
    this.waiters = new Set();
  }

  static async open({ statePath, clock = { now: () => Date.now() } }) {
    const loaded = await loadState(statePath);
    const prepared = loaded === undefined ? { state: emptyState(), migrated: false } : prepareState(loaded);
    const state = prepared.state;
    const store = new DurableStore(statePath, clock, state);
    if (loaded === undefined || prepared.migrated) await atomicWrite(statePath, state);
    return store;
  }

  get serverEpoch() { return this.state.serverEpoch; }
  get tailCursor() { return tailCursor(this.state); }
  get waiterCount() { return this.waiters.size; }

  serialize(action) {
    const result = this.queue.then(action, action);
    this.queue = result.catch(() => undefined);
    return result;
  }

  prune() {
    const before = this.state.retained.length;
    this.state.retained = this.state.retained.filter((entry) => entry.event.kind !== "android.notification" || entry.deadlineMs > this.clock.now());
    const notifications = () => this.state.retained.filter((entry) => entry.event.kind === "android.notification")
      .sort((left, right) => Number(left.cursor) - Number(right.cursor));
    let pending = notifications();
    let total = pending.reduce((sum, entry) => sum + entry.bytes, 0);
    while (pending.length > LIMITS.notificationCount || total > LIMITS.notificationBytes) {
      const evicted = pending.shift();
      this.state.retained = this.state.retained.filter((entry) => entry.cursor !== evicted.cursor);
      total -= evicted.bytes;
    }
    return before !== this.state.retained.length;
  }

  async publish(actor, rawEvent) {
    const parsed = parseEvent(actor, rawEvent);
    if (parsed.status !== 0) return parsed;
    const event = parsed.event;
    return this.serialize(async () => {
      this.prune();
      const identity = fingerprint(event);
      const previousId = this.state.dedupe.find((entry) => entry.eventId === event.eventId);
      if (previousId !== undefined) return previousId.fingerprint === identity
        ? { status: 200, cursor: previousId.cursor, idempotent: true }
        : failure("event_id_conflict");
      const key = originKey(event);
      const previousSequence = this.state.dedupe.find((entry) => entry.originKey === key && entry.sequence === event.sequence);
      if (previousSequence !== undefined) return previousSequence.fingerprint === identity
        ? { status: 200, cursor: previousSequence.cursor, idempotent: true }
        : failure("sequence_conflict");
      if (this.state.retiredEpochs.includes(key)) return failure("stale_sequence");
      const highWater = this.state.highWaters.find((entry) => entry.key === key)?.sequence ?? 0;
      if (event.sequence <= highWater) return failure("stale_sequence");
      const active = this.state.activeEpochs.find((entry) => entry.deviceId === event.originDeviceId);
      if (active !== undefined && active.epoch !== event.originEpoch && event.sequence !== 1) return failure("stale_sequence");
      const highWaterEntry = this.state.highWaters.find((entry) => entry.key === key);
      const rotatesEpoch = active !== undefined && active.epoch !== event.originEpoch;
      if ((highWaterEntry === undefined && this.state.highWaters.length >= MAX_REPLAY_ORIGIN_KEYS) ||
          (rotatesEpoch && this.state.retiredEpochs.length >= MAX_REPLAY_ORIGIN_KEYS) ||
          (active === undefined && this.state.activeEpochs.length >= MAX_REPLAY_ORIGIN_KEYS)) return failure("stale_sequence");
      if (active !== undefined && active.epoch !== event.originEpoch) {
        this.state.retiredEpochs.push(`${event.originDeviceId}\u0000${active.epoch}`);
        active.epoch = event.originEpoch;
      } else if (active === undefined) this.state.activeEpochs.push({ deviceId: event.originDeviceId, epoch: event.originEpoch });
      if (highWaterEntry === undefined) this.state.highWaters.push({ key, sequence: event.sequence });
      else highWaterEntry.sequence = event.sequence;
      const cursor = this.state.nextCursor;
      this.state.nextCursor = String(Number(cursor) + 1);
      const destination = destinationFor(event);
      if (event.kind === "clipboard.text") {
        this.state.retained = this.state.retained.filter((entry) => !(entry.destination === destination && entry.event.kind === "clipboard.text"));
      }
      const bytes = Buffer.byteLength(JSON.stringify(event), "utf8");
      const requestedDeadline = event.expiresAtMs ?? Number.MAX_SAFE_INTEGER;
      this.state.retained.push({ cursor, destination, event, bytes,
        deadlineMs: event.kind === "android.notification" ? Math.min(this.clock.now() + LIMITS.notificationTtlMs, requestedDeadline) : null });
      this.state.dedupe.push({ eventId: event.eventId, originKey: key, sequence: event.sequence,
        fingerprint: identity, cursor, originRole: event.originRole });
      if (this.state.dedupe.length > 4_096) this.state.dedupe.splice(0, this.state.dedupe.length - 4_096);
      this.prune();
      await atomicWrite(this.statePath, this.state);
      this.signal(destination);
      return { status: 201, cursor, idempotent: false };
    });
  }

  recordsFor(role, after) {
    return this.state.retained.filter((entry) => entry.destination === role && Number(entry.cursor) > Number(after))
      .sort((left, right) => Number(left.cursor) - Number(right.cursor));
  }

  async fetch(actor, after) {
    return this.serialize(async () => {
      const changed = this.prune();
      if (changed) await atomicWrite(this.statePath, this.state);
      return { status: 200, protocolVersion: 1, serverEpoch: this.serverEpoch, after,
        nextCursor: tailCursor(this.state), events: structuredClone(this.recordsFor(actor.role, after)) };
    });
  }

  async ack(actor, eventIds) {
    return this.serialize(async () => {
      for (const eventId of eventIds) {
        const retained = this.state.retained.find((entry) => entry.event.eventId === eventId);
        if (retained !== undefined && retained.destination !== actor.role) return failure("identity_mismatch");
        const known = this.state.dedupe.find((entry) => entry.eventId === eventId);
        if (retained === undefined && known !== undefined && destinationFor({ originRole: known.originRole }) !== actor.role) {
          return failure("identity_mismatch");
        }
      }
      this.prune();
      const acked = [];
      const alreadyAbsent = [];
      for (const eventId of eventIds) {
        const retained = this.state.retained.find((entry) => entry.event.eventId === eventId);
        if (retained === undefined) alreadyAbsent.push(eventId);
        else { this.state.retained = this.state.retained.filter((entry) => entry !== retained); acked.push(eventId); }
      }
      await atomicWrite(this.statePath, this.state);
      return { status: 200, acked, alreadyAbsent };
    });
  }

  signal(role) {
    for (const waiter of [...this.waiters]) if (waiter.role === role) waiter.resolve();
  }

  async waitForEvents(actor, after, waitMs, signal) {
    const initial = await this.fetch(actor, after);
    if (initial.events.length > 0 || waitMs === 0 || initial.nextCursor !== after || signal?.aborted) return initial;
    let waiter;
    const wake = new Promise((resolveWake) => {
      const cleanup = () => { clearTimeout(waiter.timer); signal?.removeEventListener("abort", waiter.resolve); this.waiters.delete(waiter); resolveWake(); };
      waiter = { role: actor.role, resolve: cleanup, timer: setTimeout(cleanup, waitMs) };
      this.waiters.add(waiter);
      signal?.addEventListener("abort", waiter.resolve, { once: true });
    });
    await wake;
    return this.fetch(actor, after);
  }
}
