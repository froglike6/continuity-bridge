const assertionReceipts = new WeakSet();

export const REQUIRED_PHASES = Object.freeze(["fixtures", "core", "adversarial", "restart", "replacement",
  "disconnect", "rotation", "corrupt-state", "sigkill", "leak-scan", "build-matrix", "canonical-hashes", "cleanup"]);
export const REQUIRED_CAPABILITIES = Object.freeze(["canonical-fixtures", "strict-http", "tls-auth", "ordering",
  "retention-bounds", "stateful-no-echo", "restart-before-ack", "restart-after-ack", "same-state-replacement",
  "disconnect-reconnect", "token-rotation", "corrupt-state-fail-closed", "sigkill-recovery", "sentinel-absence",
  "component-matrix", "canonical-unchanged", "resource-cleanup"]);

export const REQUIRED_PROOFS = Object.freeze({
  fixtures: ["fixture.schema", "fixture.scenario-count", "fixture.case-1.step-1.status",
    "fixture.case-1.step-2.eventIds", "fixture.case-1.step-3.acked", "fixture.case-2.step-2.withheld",
    "fixture.case-2.step-6.acked", "fixture.case-3.step-1.code", "fixture.case-3.step-10.code",
    "fixture.case-4.step-2.idempotent", "fixture.case-4.step-6.code", "fixture.case-5.step-3.eventIds",
    "fixture.case-6.step-3.code", "fixture.case-7.step-5.eventIds", "fixture.case-8.step-1.retained",
    "fixture.case-8.step-1.first", "fixture.case-9.step-1.bytes", "fixture.case-9.step-1.evicted",
    "fixture.case-10.step-4.before.status", "fixture.case-10.step-4.after.status",
    "fixture.case-10.step-4.serverEpoch", "fixture.case-10.step-5.eventIds",
    "fixture.case-11.step-2.payloadText", "fixture.case-12.step-3.payloadText",
    "fixture.case-1.executed", "fixture.case-2.executed", "fixture.case-3.executed", "fixture.case-4.executed",
    "fixture.case-5.executed", "fixture.case-6.executed", "fixture.case-7.executed", "fixture.case-8.executed",
    "fixture.case-9.executed", "fixture.case-10.executed", "fixture.case-11.executed", "fixture.case-12.executed"],
  core: ["health.protocol", "tls.wrong-hostname", "tls.wrong-ca", "auth.missing.code", "auth.bad.code",
    "auth.revoked.code", "macos.clip.payload", "android.clip.fetch.status", "notification.fetch.status",
    "order.stale-1-0.code", "retry.identical.idempotent",
    "retry.event-conflict.code", "retry.sequence-conflict.code", "cursor.replay", "epoch.retired.code",
    "longpoll.cancelled", "longpoll.recovery.empty", "connection.released"],
  adversarial: ["malformed.utf8.code", "invalid.version.code", "invalid.kind.code", "opaque.fetch.status", "opaque.delivered",
    "supersession.latest-only", "expiry.past", "clipboard.boundary.accepted", "clipboard.over.code",
    "clipboard.utf8-over.code", "count.fetch.status", "count.bound", "bytes.exact-bound", "echo.marker-zero-publish",
    "echo.zero-events", "echo.manual-published", "echo.post-ack.absent"],
  restart: ["restart.before.redelivered", "restart.before.epoch", "restart.before.acked",
    "restart.after.acked", "restart.after.absent", "restart.after.cursor"],
  replacement: ["replacement.epoch", "replacement.cursor"],
  disconnect: ["disconnect.health-fails", "disconnect.recovered.status", "disconnect.status"],
  rotation: ["rotation.old.code", "rotation.new.status", "rotation.new.protocol"],
  "corrupt-state": ["corrupt.fail-closed", "corrupt.preserved", "corrupt.recovered.status"],
  sigkill: ["sigkill.1.recovery-valid", "sigkill.1.normalized", "sigkill.2.recovery-valid", "sigkill.2.normalized",
    "sigkill.3.recovery-valid", "sigkill.3.post-kill.schema", "sigkill.3.post-kill.atomic",
    "sigkill.3.post-kill.dedupe-digest-only", "sigkill.3.post-kill.sentinel-absence", "sigkill.3.replay.status",
    "sigkill.3.replay.accepted", "sigkill.3.replay.cursor", "sigkill.3.replay.idempotent",
    "sigkill.3.replay-fetch.status", "sigkill.3.replay-fetch.eventIds", "sigkill.3.replay-ack.status",
    "sigkill.3.replay-ack.acked", "sigkill.3.replay-absent.status", "sigkill.3.replay-absent.events",
    "sigkill.3.normalized"],
  "leak-scan": ["leak.post-ack.android", "leak.post-ack.macos", "leak.live.retained",
    "leak.live.dedupe-count", "leak.live.dedupe-digest-only", "leak.live.sentinel-count",
    "leak.post-kill.captured", "leak.post-kill.dedupe-digest-only", "leak.post-kill.sentinel-count",
    "leak.final.health.status"],
  "build-matrix": ["matrix.relay.pass", "matrix.android.host", "matrix.android.production",
    "matrix.android.fixture", "matrix.android.apks", "matrix.swift.tests", "matrix.macos.package",
    "matrix.package-verifiers"],
  "canonical-hashes": ["canonical.product", "canonical.protocol", "canonical.relay", "canonical.android",
    "canonical.macos", "canonical.runtime", "canonical.integration", "canonical.design", "canonical.outputs",
    "hardening.user", "hardening.readonly", "hardening.capdrop", "hardening.security", "hardening.loopback",
    "hardening.restart", "hardening.pids", "hardening.memory", "hardening.tmpfs", "hardening.state-mount",
    "hardening.health"],
  cleanup: ["cleanup.containers", "cleanup.networks", "cleanup.image-preserved", "cleanup.listener", "cleanup.temp",
    "cleanup.docker-prestate"],
});

export function assertionReceipt(summary) {
  const receipt = Object.freeze({ ...summary });
  assertionReceipts.add(receipt);
  return receipt;
}

export class CompletionGate {
  constructor() { this.phases = new Map(); this.capabilities = new Set(); }

  record(phase, receipt, capabilities) {
    if (!REQUIRED_PHASES.includes(phase) || this.phases.has(phase) || !assertionReceipts.has(receipt) ||
        receipt.section !== phase || receipt.assertions < 1 || !Array.isArray(capabilities) || capabilities.length < 1) {
      throw new Error(`invalid phase proof: ${phase}`);
    }
    const provided = new Set(receipt.proofs);
    for (const proof of REQUIRED_PROOFS[phase]) {
      if (!provided.has(proof)) throw new Error(`missing assertion proof: ${phase}:${proof}`);
    }
    this.phases.set(phase, receipt);
    for (const capability of capabilities) this.capabilities.add(capability);
  }

  phaseMarker(phase) {
    const receipt = this.phases.get(phase);
    if (receipt === undefined) throw new Error(`missing phase: ${phase}`);
    return { event: "LOCAL_CONTRACT_PHASE_OK", phase, assertions: receipt.assertions, classes: receipt.classes };
  }

  finish() {
    for (const phase of REQUIRED_PHASES) if (!this.phases.has(phase)) throw new Error(`missing phase: ${phase}`);
    for (const capability of REQUIRED_CAPABILITIES) {
      if (!this.capabilities.has(capability)) throw new Error(`missing capability: ${capability}`);
    }
    return { event: "LOCAL_CONTRACT_INTEGRATION_OK", phases: this.phases.size,
      assertions: [...this.phases.values()].reduce((sum, receipt) => sum + receipt.assertions, 0),
      classes: [...this.phases.values()].reduce((sum, receipt) => sum + receipt.classes, 0) };
  }
}
