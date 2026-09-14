import { atomicWrite, cleanupTempFiles, loadState } from "./persistence.mjs";
import { isClipboard, LIMITS, parseEvent } from "./schema.mjs";
import { destinationFor, emptyState, fingerprint, MAX_REPLAY_ORIGIN_KEYS, prepareState, RECENT_IDENTITY_COUNT } from "./state-model.mjs";
import { failure, StateError } from "./errors.mjs";

const originKey = (event) => `${event.originDeviceId}\u0000${event.originEpoch}`;
const tailCursor = (state) => String(Number(state.nextCursor) - 1);

export class DurableStore {
  constructor(statePath, clock, state, writeState = atomicWrite) {
    this.statePath = statePath;
    this.clock = clock;
    this.state = state;
    this.writeState = writeState;
    this.unavailableError = undefined;
    this.queue = Promise.resolve();
    this.waiters = new Set();
  }

  static async open({ statePath, clock = { now: () => Date.now() }, writeState = atomicWrite }) {
    await cleanupTempFiles(statePath);
    const loaded = await loadState(statePath);
    const prepared = loaded === undefined ? { state: emptyState(), migrated: false } : prepareState(loaded);
    const state = prepared.state;
    const store = new DurableStore(statePath, clock, state, writeState);
    if (loaded === undefined || prepared.migrated) await writeState(statePath, state);
    return store;
  }

  get serverEpoch() { return this.state.serverEpoch; }
  get tailCursor() { return tailCursor(this.state); }
  get waiterCount() { return this.waiters.size; }

  serialize(action) {
    const guarded = () => {
      if (this.unavailableError !== undefined) throw this.unavailableError;
      return action();
    };
    const result = this.queue.then(guarded, guarded);
    this.queue = result.catch(() => undefined);
    return result;
  }

  recover() {
    const restore = async () => {
      if (this.unavailableError === undefined) return true;
      try {
        await cleanupTempFiles(this.statePath);
        const loaded = await loadState(this.statePath);
        if (loaded === undefined) throw new StateError(new Error("persisted state disappeared during recovery"));
        const prepared = prepareState(loaded);
        await this.writeState(this.statePath, prepared.state);
        this.state = prepared.state;
        this.unavailableError = undefined;
        this.signal("android");
        this.signal("macos");
        return true;
      } catch (error) {
        this.unavailableError = error instanceof StateError ? error : new StateError(error);
        return false;
      }
    };
    const result = this.queue.then(restore, restore);
    this.queue = result.catch(() => undefined);
    return result;
  }

  prune(state) {
    const before = state.retained.length;
    state.retained = state.retained.filter((entry) => entry.event.kind !== "android.notification" || entry.deadlineMs > this.clock.now());
    const notifications = () => state.retained.filter((entry) => entry.event.kind === "android.notification")
      .sort((left, right) => Number(left.cursor) - Number(right.cursor));
    let pending = notifications();
    let total = pending.reduce((sum, entry) => sum + entry.bytes, 0);
    while (pending.length > LIMITS.notificationCount || total > LIMITS.notificationBytes) {
      const evicted = pending.shift();
      state.retained = state.retained.filter((entry) => entry.cursor !== evicted.cursor);
      total -= evicted.bytes;
    }
    return before !== state.retained.length;
  }

  trimDedupe(state) {
    const oldestRecentCursor = Number(state.nextCursor) - RECENT_IDENTITY_COUNT;
    const retainedIds = new Set(state.retained.map((entry) => entry.event.eventId));
    state.dedupe = state.dedupe.filter((entry) =>
      Number(entry.cursor) >= oldestRecentCursor || retainedIds.has(entry.eventId));
  }

  async commit(candidate) {
    this.trimDedupe(candidate);
    try {
      await this.writeState(this.statePath, candidate);
    } catch (error) {
      this.unavailableError = error instanceof StateError ? error : new StateError(error);
      throw this.unavailableError;
    }
    this.state = candidate;
  }

  async publish(actor, rawEvent) {
    const parsed = parseEvent(actor, rawEvent);
    if (parsed.status !== 0) return parsed;
    const event = parsed.event;
    return this.serialize(async () => {
      const candidate = structuredClone(this.state);
      this.prune(candidate);
      const identity = fingerprint(event);
      const previousId = candidate.dedupe.find((entry) => entry.eventId === event.eventId);
      if (previousId !== undefined) return previousId.fingerprint === identity
        ? { status: 200, cursor: previousId.cursor, idempotent: true }
        : failure("event_id_conflict");
      const key = originKey(event);
      const previousSequence = candidate.dedupe.find((entry) => entry.originKey === key && entry.sequence === event.sequence);
      if (previousSequence !== undefined) return previousSequence.fingerprint === identity
        ? { status: 200, cursor: previousSequence.cursor, idempotent: true }
        : failure("sequence_conflict");
      if (candidate.retiredEpochs.includes(key)) return failure("stale_sequence");
      const highWater = candidate.highWaters.find((entry) => entry.key === key)?.sequence ?? 0;
      if (event.sequence <= highWater) return failure("stale_sequence");
      const active = candidate.activeEpochs.find((entry) => entry.deviceId === event.originDeviceId);
      if (active !== undefined && active.epoch !== event.originEpoch && event.sequence !== 1) return failure("stale_sequence");
      const highWaterEntry = candidate.highWaters.find((entry) => entry.key === key);
      const rotatesEpoch = active !== undefined && active.epoch !== event.originEpoch;
      if ((highWaterEntry === undefined && candidate.highWaters.length >= MAX_REPLAY_ORIGIN_KEYS) ||
          (rotatesEpoch && candidate.retiredEpochs.length >= MAX_REPLAY_ORIGIN_KEYS) ||
          (active === undefined && candidate.activeEpochs.length >= MAX_REPLAY_ORIGIN_KEYS)) return failure("stale_sequence");
      if (active !== undefined && active.epoch !== event.originEpoch) {
        candidate.retiredEpochs.push(`${event.originDeviceId}\u0000${active.epoch}`);
        active.epoch = event.originEpoch;
      } else if (active === undefined) candidate.activeEpochs.push({ deviceId: event.originDeviceId, epoch: event.originEpoch });
      if (highWaterEntry === undefined) candidate.highWaters.push({ key, sequence: event.sequence });
      else highWaterEntry.sequence = event.sequence;
      const cursor = candidate.nextCursor;
      candidate.nextCursor = String(Number(cursor) + 1);
      const destination = destinationFor(event);
      if (isClipboard(event.kind)) {
        candidate.retained = candidate.retained.filter((entry) => !(entry.event.originDeviceId === event.originDeviceId && isClipboard(entry.event.kind)));
      }
      const bytes = Buffer.byteLength(JSON.stringify(event), "utf8");
      const requestedDeadline = event.expiresAtMs ?? Number.MAX_SAFE_INTEGER;
      candidate.retained.push({ cursor, destination, event, bytes,
        deadlineMs: event.kind === "android.notification" ? Math.min(this.clock.now() + LIMITS.notificationTtlMs, requestedDeadline) : null });
      candidate.dedupe.push({ eventId: event.eventId, originKey: key, sequence: event.sequence,
        fingerprint: identity, cursor, originRole: event.originRole });
      this.prune(candidate);
      await this.commit(candidate);
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
      const candidate = structuredClone(this.state);
      const changed = this.prune(candidate);
      if (changed) await this.commit(candidate);
      const page = { status: 200, protocolVersion: 1, serverEpoch: this.serverEpoch, after,
        nextCursor: tailCursor(this.state), events: [] };
      let length = Buffer.byteLength(JSON.stringify(page), "utf8");
      for (const entry of this.recordsFor(actor.role, after)) {
        const entryBytes = Buffer.byteLength(JSON.stringify(entry), "utf8") + (page.events.length ? 1 : 0);
        if (length + entryBytes > LIMITS.responseBody) {
          page.nextCursor = page.events.at(-1)?.cursor ?? after;
          break;
        }
        page.events.push(entry);
        length += entryBytes;
      }
      return structuredClone(page);
    });
  }

  async ack(actor, eventIds) {
    return this.serialize(async () => {
      const candidate = structuredClone(this.state);
      for (const eventId of eventIds) {
        const retained = candidate.retained.find((entry) => entry.event.eventId === eventId);
        if (retained !== undefined && retained.destination !== actor.role) return failure("identity_mismatch");
        const known = candidate.dedupe.find((entry) => entry.eventId === eventId);
        if (retained === undefined && known !== undefined && destinationFor({ originRole: known.originRole }) !== actor.role) {
          return failure("identity_mismatch");
        }
      }
      this.prune(candidate);
      const acked = [];
      const alreadyAbsent = [];
      for (const eventId of eventIds) {
        const retained = candidate.retained.find((entry) => entry.event.eventId === eventId);
        if (retained === undefined) alreadyAbsent.push(eventId);
        else { candidate.retained = candidate.retained.filter((entry) => entry !== retained); acked.push(eventId); }
      }
      await this.commit(candidate);
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
