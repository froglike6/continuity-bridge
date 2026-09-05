import assert from "node:assert/strict";
import { isDeepStrictEqual } from "node:util";
import { assertionReceipt } from "./completion-gate.mjs";

export class Assertions {
  constructor() { this.counts = new Map(); }

  equal(name, actual, expected) {
    if (!isDeepStrictEqual(actual, expected)) throw new Error(`assertion failed: ${name}`);
    this.counts.set(name, (this.counts.get(name) ?? 0) + 1);
  }

  ok(name, value) {
    assert.ok(value, name);
    this.counts.set(name, (this.counts.get(name) ?? 0) + 1);
  }

  async rejects(name, operation) {
    await assert.rejects(operation, name);
    this.counts.set(name, (this.counts.get(name) ?? 0) + 1);
  }

  summary(section) {
    return assertionReceipt({ section, assertions: [...this.counts.values()].reduce((sum, count) => sum + count, 0),
      classes: this.counts.size, proofs: Object.freeze([...this.counts.keys()]) });
  }
}

export function status(assertions, name, response, expectedStatus, expectedCode) {
  assertions.equal(`${name}.status`, response.status, expectedStatus);
  if (expectedCode !== undefined) assertions.equal(`${name}.code`, response.body?.error?.code, expectedCode);
}

export function accepted(assertions, name, response, expectedStatus, expectedCursor, idempotent = false) {
  status(assertions, name, response, expectedStatus);
  assertions.equal(`${name}.cursor`, response.body?.cursor, expectedCursor);
  assertions.equal(`${name}.idempotent`, response.body?.idempotent, idempotent);
  assertions.equal(`${name}.accepted`, response.body?.accepted, true);
}
