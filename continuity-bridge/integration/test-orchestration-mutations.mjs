import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { Assertions } from "./assertions.mjs";
import { canonicalSnapshot, nodeTestSummaryPassed, swiftTestSummaryPassed, treeDigest } from "./build-matrix.mjs";
import { CompletionGate, REQUIRED_CAPABILITIES, REQUIRED_PHASES, REQUIRED_PROOFS } from "./completion-gate.mjs";
import { DockerHarness } from "./local-docker.mjs";
import { isFailedClosedContainerState } from "./orchestration-phases.mjs";

function completeReceipt(phase) {
  const assertions = new Assertions();
  for (const proof of REQUIRED_PROOFS[phase]) assertions.equal(proof, true, true);
  return assertions.summary(phase);
}

test("Given each required phase is removed When full completion is requested Then global success is impossible", () => {
  for (const removed of REQUIRED_PHASES) {
    const gate = new CompletionGate();
    for (const phase of REQUIRED_PHASES.filter((candidate) => candidate !== removed)) {
      gate.record(phase, completeReceipt(phase), REQUIRED_CAPABILITIES);
    }
    assert.throws(() => gate.finish(), { message: `missing phase: ${removed}` });
  }
});

test("Given fabricated counters or response labels When completion is requested Then they cannot prove a phase", () => {
  const gate = new CompletionGate();
  assert.throws(() => gate.record("core", { section: "core", assertions: 999, classes: 999 }, ["strict-http"]));
});

test("Given a partial phase When its marker is emitted Then it is never the global marker", () => {
  const gate = new CompletionGate();
  assert.throws(() => gate.phaseMarker("core"), { message: "missing phase: core" });
});

test("Given a response body assertion is bypassed When the phase is recorded Then the phase is rejected", () => {
  const gate = new CompletionGate();
  const assertions = new Assertions();
  assertions.equal("health.status", 200, 200);
  assert.throws(() => gate.record("core", assertions.summary("core"), ["strict-http", "tls-auth", "ordering"]));
});

test("Given the no-echo marker assertion is faked or omitted When adversarial is recorded Then it is rejected", () => {
  const gate = new CompletionGate();
  const assertions = new Assertions();
  assertions.equal("echo.zero-events", [], []);
  assert.throws(() => gate.record("adversarial", assertions.summary("adversarial"),
    ["retention-bounds", "stateful-no-echo"]));
});

test("Given sentinel cleanup is omitted When leak scan is recorded Then it is rejected", () => {
  const gate = new CompletionGate();
  const assertions = new Assertions();
  assertions.equal("leak.final.retained", [], []);
  assert.throws(() => gate.record("leak-scan", assertions.summary("leak-scan"), ["sentinel-absence"]));
});

test("Given live state leak scanning When orchestration is inspected Then scanning precedes every reset", async () => {
  const source = await readFile(new URL("./orchestration-final.mjs", import.meta.url), "utf8");
  const scan = source.indexOf("const liveStateText = await readFile(harness.statePath");
  const reset = source.indexOf("harness.resetState", source.indexOf("export async function leakPhase"));
  assert.ok(scan >= 0 && (reset < 0 || scan < reset));
});

test("Given response bodies are consumed When scenarios are inspected Then every named fetch has a status proof", async () => {
  const core = await readFile(new URL("./scenarios-core.mjs", import.meta.url), "utf8");
  const adversarial = await readFile(new URL("./scenarios-adversarial.mjs", import.meta.url), "utf8");
  for (const proof of ["android.clip.fetch", "notification.fetch"]) assert.ok(core.includes(`status(a, "${proof}"`));
  for (const proof of ["opaque.fetch", "count.fetch"]) assert.ok(adversarial.includes(`status(a, "${proof}"`));
});

test("Given a full-run canonical snapshot When integration changes Then its digest is part of the manifest", async () => {
  const workspace = new URL("../../", import.meta.url).pathname;
  const snapshot = await canonicalSnapshot(workspace);
  assert.equal(snapshot.integration, await treeDigest(new URL("./", import.meta.url).pathname));
  const image = "sha256:a0c6f3f6bb56bcb0b478b31cbf0d7e949e7cd2d2efa821c6c238c18845233ece";
  assert.equal(new DockerHarness(workspace, { TASK7_RELAY_IMAGE: image }).image, image);
});

test("Given the relay image input is absent or mutable When the harness is created Then it fails closed", () => {
  const workspace = new URL("../../", import.meta.url).pathname;
  assert.throws(() => new DockerHarness(workspace, {}), { message: "TASK7_RELAY_IMAGE is required" });
  assert.throws(() => new DockerHarness(workspace, { TASK7_RELAY_IMAGE: "continuity-bridge-relay:task2" }),
    { message: "TASK7_RELAY_IMAGE must be an immutable sha256 image ID" });
});

test("Given the full Docker harness compose When written Then restart and health hardening are present", () => {
  const workspace = new URL("../../", import.meta.url).pathname;
  const harness = new DockerHarness(workspace, { TASK7_RELAY_IMAGE: `sha256:${"a".repeat(64)}` });
  harness.runtime = "/tmp/task7-runtime";
  harness.caPath = "/tmp/task7-runtime/tls/ca.pem";
  harness.authPath = "/tmp/task7-runtime/auth/auth.json";
  const compose = harness.compose();
  assert.ok(compose.includes("restart: unless-stopped"));
  assert.ok(compose.includes("healthcheck:"));
  assert.ok(compose.includes("pids_limit: 64"));
  assert.ok(compose.includes("mem_limit: 128m"));
});

test("Given restart policy observes corrupt state crash When inspected Then restart-loop is still fail-closed", () => {
  assert.equal(isFailedClosedContainerState({ Running: false, ExitCode: 1, RestartCount: 0 }), true);
  assert.equal(isFailedClosedContainerState({ Running: true, ExitCode: 1, Restarting: true, RestartCount: 1 }), true);
  assert.equal(isFailedClosedContainerState({ RestartCount: 1,
    State: { Running: true, ExitCode: 1, Restarting: true, Health: { Status: "unhealthy" } } }), true);
  assert.equal(isFailedClosedContainerState({ Running: true, ExitCode: 1, RestartCount: 1,
    Health: { Status: "starting" } }), true);
  assert.equal(isFailedClosedContainerState({ Running: true, ExitCode: 0, RestartCount: 1,
    Health: { Status: "healthy" } }), false);
});

test("Given relay suite grows When matrix reads Node test output Then it accepts pass-positive fail-zero", () => {
  assert.equal(nodeTestSummaryPassed("tests 30\npass 30\nfail 0\n"), true);
  assert.equal(nodeTestSummaryPassed("ℹ tests 30\nℹ pass 30\nℹ fail 0\n"), true);
  assert.equal(nodeTestSummaryPassed("tests 30\npass 29\nfail 1\n"), false);
});

test("Given Swift suite grows When matrix reads XCTest output Then it accepts executed-positive zero-failure", () => {
  assert.equal(swiftTestSummaryPassed("Executed 50 tests, with 0 failures (0 unexpected)"), true);
  assert.equal(swiftTestSummaryPassed("Executed 50 tests, with 1 failures (0 unexpected)"), false);
});

test("Given a successful live leak scan When its receipt is written Then zero sentinel and exact surface sizes are recorded", async () => {
  const source = await readFile(new URL("./orchestration-final.mjs", import.meta.url), "utf8");
  for (const field of ["sentinelCount", "stateBytes", "logBytes"]) assert.ok(source.includes(`${field}:`));
});

test("Given the runner CLI When arguments are not exactly full Then global execution is unreachable", async () => {
  const source = await readFile(new URL("./run-local-contract-tests.mjs", import.meta.url), "utf8");
  assert.ok(source.includes("process.argv.slice(2)"));
  assert.ok(source.includes('args.length !== 1 || args[0] !== "full"'));
  assert.ok(!source.includes('process.argv[2] ?? "full"'));
  const runner = new URL("./run-local-contract-tests.mjs", import.meta.url);
  for (const args of [[], ["bogus"], ["full", "extra"]]) {
    const result = spawnSync(process.execPath, [runner.pathname, ...args], { encoding: "utf8", timeout: 5_000 });
    assert.equal(result.status, 2);
    assert.equal(result.stdout.includes("LOCAL_CONTRACT_INTEGRATION_OK"), false);
  }
});

test("Given SIGKILL round three When state is restored Then actual bytes are inspected and frozen first", async () => {
  const source = await readFile(new URL("./orchestration-final.mjs", import.meta.url), "utf8");
  const capture = source.indexOf("const postKillStateText = await readFile(harness.statePath");
  const inspect = source.indexOf("inspectPostKillState(", capture);
  const restore = source.indexOf("await writeFile(harness.statePath, beforeState", capture);
  assert.ok(capture >= 0 && inspect > capture && restore > inspect);
  assert.ok(source.includes("postKillEvidence.stateSha256"));
});

test("Given wrong duplicate stale raw or secret post-kill state When inspected Then completion proof is rejected", async () => {
  const { inspectPostKillState } = await import("./orchestration-final.mjs");
  const actor = { deviceId: "device-android-fixture", role: "android" };
  const eventFor = (number) => ({ protocolVersion: 1, eventId: `kill-${number}`, originDeviceId: actor.deviceId,
    originRole: actor.role, originEpoch: `epoch-${number}`, sequence: 1, kind: "clipboard.text",
    createdAtMs: 1_800_000_000_000, payload: { text: `payload-${number}` } });
  const digest = (value) => createHash("sha256").update(JSON.stringify(value)).digest("hex");
  const retained = (value, cursor) => ({ cursor, destination: "macos", event: value,
    bytes: Buffer.byteLength(JSON.stringify(value)), deadlineMs: null });
  const dedupe = (value, cursor) => ({ eventId: value.eventId,
    originKey: `${value.originDeviceId}\u0000${value.originEpoch}`, sequence: value.sequence,
    fingerprint: digest(value), cursor, originRole: value.originRole });
  const prior = eventFor(2);
  const candidate = eventFor(3);
  const before = { schemaVersion: 1, serverEpoch: "a".repeat(32), nextCursor: "3",
    retained: [retained(prior, "2")],
    highWaters: [{ key: `${actor.deviceId}\u0000epoch-2`, sequence: 1 }],
    activeEpochs: [{ deviceId: actor.deviceId, epoch: "epoch-2" }],
    retiredEpochs: [`${actor.deviceId}\u0000epoch-1`],
    dedupe: [dedupe(eventFor(1), "1"), dedupe(prior, "2")] };
  const next = { ...structuredClone(before), nextCursor: "4", retained: [retained(candidate, "3")],
    highWaters: [...before.highWaters, { key: `${actor.deviceId}\u0000epoch-3`, sequence: 1 }],
    activeEpochs: [{ deviceId: actor.deviceId, epoch: "epoch-3" }],
    retiredEpochs: [...before.retiredEpochs, `${actor.deviceId}\u0000epoch-2`],
    dedupe: [...before.dedupe, dedupe(candidate, "3")] };
  const input = { beforeText: `${JSON.stringify(before)}\n`, beforeTail: "2", afterTail: "3", candidate,
    sentinels: ["RAW_SECRET_SENTINEL"] };
  assert.equal(inspectPostKillState({ ...input, postKillText: `${JSON.stringify(next)}\n` }).branch, "new");
  assert.equal(inspectPostKillState({ ...input, afterTail: "2", postKillText: `${JSON.stringify(before)}\n` }).branch, "old");
  const mutations = [
    (value) => { value.retained[0].event.eventId = "wrong"; },
    (value) => { value.retained.push(structuredClone(value.retained[0])); },
    (value) => { value.highWaters.at(-1).sequence = 2; },
    (value) => { value.dedupe.at(-1).fingerprint = JSON.stringify(candidate); },
    (value) => { value.retiredEpochs.push("RAW_SECRET_SENTINEL"); },
  ];
  for (const mutate of mutations) {
    const value = structuredClone(next);
    mutate(value);
    assert.throws(() => inspectPostKillState({ ...input, postKillText: `${JSON.stringify(value)}\n` }));
  }
});
