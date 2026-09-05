import { mkdir, stat } from "node:fs/promises";
import { join } from "node:path";
import { Assertions } from "./assertions.mjs";
import { digestFile, digestTree } from "./integrity-paths.mjs";
import { copyProduct } from "./local-docker.mjs";
import { run } from "./process-runner.mjs";

const excluded = new Set([".build", "build", "dist"]);

export async function treeDigest(root) {
  return digestTree(root, { excludedDirectoryNames: excluded });
}

export function nodeTestSummaryPassed(stdout) {
  const pass = stdout.match(/(?:^|\n).*pass (\d+)(?:\n|$)/);
  return pass !== null && Number(pass[1]) > 0 && /(?:^|\n).*fail 0(?:\n|$)/.test(stdout);
}

export function swiftTestSummaryPassed(stdout) {
  const executed = stdout.match(/Executed (\d+) tests, with 0 failures/);
  return executed !== null && Number(executed[1]) > 0;
}

export async function canonicalSnapshot(workspace) {
  const product = join(workspace, "continuity-bridge");
  return Object.freeze({ product: await treeDigest(product), protocol: await treeDigest(join(product, "protocol")),
    relay: await treeDigest(join(product, "relay")), android: await treeDigest(join(product, "android")),
    macos: await treeDigest(join(product, "macos")), runtime: await treeDigest(join(product, "runtime")),
    integration: await treeDigest(join(product, "integration")), design: await digestFile(join(product, "DESIGN.md")),
    outputs: await treeDigest(join(workspace, "outputs")) });
}

export async function runBuildMatrix(workspace, directory) {
  const a = new Assertions();
  const matrix = join(directory, "matrix");
  await mkdir(matrix, { recursive: true });
  await copyProduct(join(workspace, "continuity-bridge"), join(matrix, "continuity-bridge"));
  await mkdir(join(matrix, "outputs"), { recursive: true });

  const relay = await run("node", ["--test", "continuity-bridge/relay/test/crash-recovery.test.mjs",
    "continuity-bridge/relay/test/fix-regressions.test.mjs", "continuity-bridge/relay/test/long-poll-logger.test.mjs",
    "continuity-bridge/relay/test/schema-auth.test.mjs", "continuity-bridge/relay/test/server-e2e.test.mjs",
    "continuity-bridge/relay/test/store-core.test.mjs", "continuity-bridge/relay/test/store-retention.test.mjs",
    "continuity-bridge/relay/test/strict-boundary.test.mjs"], { cwd: matrix, timeoutMs: 60_000 });
  a.ok("matrix.relay.pass", nodeTestSummaryPassed(relay.stdout));

  const host = await run(join(matrix, "continuity-bridge", "android", "run-host-tests.sh"), [], { cwd: matrix, timeoutMs: 60_000 });
  a.ok("matrix.android.host", host.stdout.includes("PRODUCTION_ENGINE_OK") && host.stdout.includes("HOST_SUITE_OK") &&
    host.stdout.includes("ADAPTER_HOST_OK"));
  const production = await run(join(matrix, "continuity-bridge", "android", "build.sh"), [], { cwd: matrix, timeoutMs: 60_000 });
  a.ok("matrix.android.production", production.stdout.includes("ANDROID_BUILD_OK"));
  const fixture = await run(join(matrix, "continuity-bridge", "android", "build-fixture.sh"), [], { cwd: matrix, timeoutMs: 60_000 });
  a.ok("matrix.android.fixture", fixture.stdout.includes("FIXTURE_BUILD_OK"));
  a.ok("matrix.android.apks", (await stat(join(matrix, "outputs", "continuity-bridge-android-debug.apk"))).size > 0 &&
    (await stat(join(matrix, "outputs", "continuity-fixture-debug.apk"))).size > 0);

  const swift = await run("swift", ["test", "--package-path", "continuity-bridge/macos"], { cwd: matrix, timeoutMs: 60_000 });
  a.ok("matrix.swift.tests", swiftTestSummaryPassed(swift.stdout));
  const app = await run(join(matrix, "continuity-bridge", "macos", "scripts", "build-app.sh"), [], { cwd: matrix, timeoutMs: 60_000 });
  a.ok("matrix.macos.package", app.stdout.includes("SHA256=") &&
    (await stat(join(matrix, "outputs", "ContinuityBridge.app.zip"))).size > 0);
  await run("codesign", ["--verify", "--deep", "--strict", join(matrix, "continuity-bridge", "macos", "dist", "ContinuityBridge.app")]);
  await run("unzip", ["-tq", join(matrix, "outputs", "ContinuityBridge.app.zip")]);
  a.equal("matrix.package-verifiers", true, true);
  return a.summary("build-matrix");
}
