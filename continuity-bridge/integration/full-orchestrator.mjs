import { writeFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { canonicalSnapshot, runBuildMatrix } from "./build-matrix.mjs";
import { CompletionGate } from "./completion-gate.mjs";
import { DockerHarness } from "./local-docker.mjs";
import { canonicalPhase, cleanupPhase, leakPhase, sigkillPhase } from "./orchestration-final.mjs";
import { corruptStatePhase, disconnectPhase, replacementPhase, restartPhase, rotationPhase } from "./orchestration-phases.mjs";
import { run } from "./process-runner.mjs";
import { runAdversarial } from "./scenarios-adversarial.mjs";
import { runCore } from "./scenarios-core.mjs";
import { runFixtures } from "./scenarios-fixtures.mjs";

const phaseCapabilities = Object.freeze({
  fixtures: ["canonical-fixtures"], core: ["strict-http", "tls-auth", "ordering"],
  adversarial: ["retention-bounds", "stateful-no-echo"], restart: ["restart-before-ack", "restart-after-ack"],
  replacement: ["same-state-replacement"], disconnect: ["disconnect-reconnect"], rotation: ["token-rotation"],
  "corrupt-state": ["corrupt-state-fail-closed"], sigkill: ["sigkill-recovery"],
  "leak-scan": ["sentinel-absence"], "build-matrix": ["component-matrix"],
  "canonical-hashes": ["canonical-unchanged"], cleanup: ["resource-cleanup"],
});

const workspaceRoot = () => resolve(dirname(fileURLToPath(import.meta.url)), "../..");

function record(gate, markers, phase, receipt) {
  gate.record(phase, receipt, phaseCapabilities[phase]);
  markers.push(gate.phaseMarker(phase));
}

export async function runFull() {
  const workspace = workspaceRoot();
  const occupied = await run("lsof", ["-nP", "-iTCP:8443", "-sTCP:LISTEN"], { acceptCodes: [0, 1] });
  if (occupied.code === 0) throw new Error("loopback port 8443 is already occupied");
  const before = await canonicalSnapshot(workspace);
  const harness = new DockerHarness(workspace);
  const gate = new CompletionGate();
  const markers = [];
  let cleaned = false;
  try {
    let config = await harness.initialize();
    record(gate, markers, "fixtures", await runFixtures(harness, config));
    config = await harness.resetState("fixtures");
    record(gate, markers, "core", await runCore(config));
    record(gate, markers, "adversarial", await runAdversarial(config));
    config = await harness.resetState("contract");
    record(gate, markers, "restart", await restartPhase(harness, config));
    record(gate, markers, "replacement", await replacementPhase(harness, config));
    record(gate, markers, "disconnect", await disconnectPhase(harness, config));
    const rotation = await rotationPhase(harness, config);
    config = rotation.config;
    record(gate, markers, "rotation", rotation.receipt);
    record(gate, markers, "corrupt-state", await corruptStatePhase(harness));
    const sigkill = await sigkillPhase(harness, config);
    record(gate, markers, "sigkill", sigkill.receipt);
    const leak = await leakPhase(harness, config, sigkill.evidence);
    config = leak.config;
    record(gate, markers, "leak-scan", leak.receipt);
    record(gate, markers, "build-matrix", await runBuildMatrix(workspace, harness.directory));
    record(gate, markers, "canonical-hashes", await canonicalPhase(harness, before, await canonicalSnapshot(workspace)));
    const docker = await harness.imageMetadata();
    record(gate, markers, "cleanup", await cleanupPhase(harness));
    cleaned = true;
    const global = gate.finish();
    const result = Object.freeze({ markers, global, hashes: before, docker, liveState: leak.metadata });
    if (process.env.TASK7_RECEIPT_PATH !== undefined) {
      await writeFile(process.env.TASK7_RECEIPT_PATH, `${JSON.stringify(result)}\n`, { mode: 0o600 });
    }
    return result;
  } finally {
    if (!cleaned) await harness.cleanup();
  }
}

export async function runPartial(phase) {
  if (process.env.RELAY_CA_PATH === undefined || process.env.RELAY_AUTH_PATH === undefined) {
    throw new Error("partial phase requires explicit relay inputs");
  }
  const { loadConfig } = await import("./http-client.mjs");
  const config = await loadConfig();
  const runners = { core: runCore, adversarial: runAdversarial };
  if (runners[phase] === undefined) throw new Error("unsupported partial phase");
  const receipt = await runners[phase](config);
  const gate = new CompletionGate();
  gate.record(phase, receipt, phaseCapabilities[phase]);
  return gate.phaseMarker(phase);
}
