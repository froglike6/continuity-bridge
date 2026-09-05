import { createHash } from "node:crypto";
import { readFile, stat, writeFile } from "node:fs/promises";
import { isDeepStrictEqual } from "node:util";
import { Assertions, status } from "./assertions.mjs";
import { ack, event, request } from "./http-client.mjs";
import { run } from "./process-runner.mjs";
import { fingerprint, validateState } from "../relay/src/state-model.mjs";

const digestPattern = /^[0-9a-f]{64}$/;

function expectedPublishedState(before, candidate) {
  const expected = structuredClone(before);
  const key = `${candidate.originDeviceId}\u0000${candidate.originEpoch}`;
  const cursor = before.nextCursor;
  expected.nextCursor = String(Number(cursor) + 1);
  const active = expected.activeEpochs.find((entry) => entry.deviceId === candidate.originDeviceId);
  if (active === undefined) expected.activeEpochs.push({ deviceId: candidate.originDeviceId, epoch: candidate.originEpoch });
  else if (active.epoch !== candidate.originEpoch) {
    expected.retiredEpochs.push(`${candidate.originDeviceId}\u0000${active.epoch}`);
    active.epoch = candidate.originEpoch;
  }
  const highWater = expected.highWaters.find((entry) => entry.key === key);
  if (highWater === undefined) expected.highWaters.push({ key, sequence: candidate.sequence });
  else highWater.sequence = candidate.sequence;
  expected.retained = expected.retained.filter((entry) =>
    !(entry.destination === "macos" && entry.event.kind === "clipboard.text"));
  expected.retained.push({ cursor, destination: "macos", event: candidate,
    bytes: Buffer.byteLength(JSON.stringify(candidate), "utf8"), deadlineMs: null });
  expected.dedupe.push({ eventId: candidate.eventId, originKey: key, sequence: candidate.sequence,
    fingerprint: fingerprint(candidate), cursor, originRole: candidate.originRole });
  return expected;
}

export function inspectPostKillState({ beforeText, postKillText, beforeTail, afterTail, candidate, sentinels }) {
  const before = validateState(JSON.parse(beforeText));
  const actual = JSON.parse(postKillText);
  const validated = validateState(actual);
  const expectedNew = expectedPublishedState(before, candidate);
  const old = afterTail === beforeTail && isDeepStrictEqual(validated, before);
  const nextTail = String(Number(beforeTail) + 1);
  const fresh = afterTail === nextTail && isDeepStrictEqual(validated, expectedNew);
  if (!old && !fresh) throw new Error("post-kill state is not the exact old-or-new atomic state");
  const digestFingerprintCount = actual.dedupe.filter((entry) => digestPattern.test(entry.fingerprint)).length;
  if (digestFingerprintCount !== actual.dedupe.length) throw new Error("post-kill dedupe contains non-digest data");
  const sentinelCount = sentinels.filter((sentinel) => postKillText.includes(sentinel)).length;
  if (sentinelCount !== 0) throw new Error("post-kill state contains a forbidden raw sentinel");
  return Object.freeze({ branch: old ? "old" : "new",
    stateSha256: createHash("sha256").update(postKillText).digest("hex"), stateBytes: Buffer.byteLength(postKillText),
    retainedCount: actual.retained.length, dedupeCount: actual.dedupe.length,
    digestFingerprintCount, sentinelCount });
}

export async function sigkillPhase(harness, config) {
  const a = new Assertions();
  let postKillEvidence;
  for (let round = 1; round <= 3; round += 1) {
    const before = await request(config);
    status(a, `sigkill.${round}.before`, before, 200);
    const beforeState = await readFile(harness.statePath);
    const candidate = event(config.active.android, { eventId: `task7-full-kill-${round}`,
      originEpoch: `task7-full-kill-epoch-${round}`, sequence: 1, payload: { text: "k".repeat(1_048_576) } });
    const publishing = request(config, { token: config.active.android.token, method: "POST", path: "/v1/events",
      body: candidate, timeoutMs: 10_000 }).then((value) => ({ status: "fulfilled", value }),
      (reason) => ({ status: "rejected", reason }));
    await new Promise((resolve) => setTimeout(resolve, 5));
    await run("docker", ["kill", "--signal", "KILL", harness.container()]);
    await publishing;
    await run("docker", ["start", harness.container()]);
    await harness.waitHealthy();
    const after = await request(config);
    status(a, `sigkill.${round}.after`, after, 200);
    a.equal(`sigkill.${round}.epoch`, after.body.serverEpoch, before.body.serverEpoch);
    const state = JSON.parse(await readFile(harness.statePath, "utf8"));
    const oldOrNew = [Number(before.body.tailCursor), Number(before.body.tailCursor) + 1].includes(Number(after.body.tailCursor));
    a.ok(`sigkill.${round}.recovery-valid`, oldOrNew && state.schemaVersion === 1 && Array.isArray(state.retained));
    if (round < 3) {
      const normalized = await request(config, { token: config.active.android.token, method: "POST", path: "/v1/events",
        body: candidate, timeoutMs: 10_000 });
      const expectedStatus = Number(after.body.tailCursor) === Number(before.body.tailCursor) ? 201 : 200;
      a.ok(`sigkill.${round}.normalized`, normalized.status === expectedStatus && normalized.body?.accepted === true &&
        normalized.body?.cursor === String(Number(before.body.tailCursor) + 1) && normalized.body?.idempotent === (expectedStatus === 200));
    } else {
      const postKillStateText = await readFile(harness.statePath, "utf8");
      const auth = JSON.parse(await readFile(harness.authPath, "utf8"));
      const sentinels = [...auth.credentials.map((entry) => entry.token), "-----BEGIN PRIVATE KEY-----",
        "-----BEGIN RSA PRIVATE KEY-----", "TASK7_PAYLOAD_SENTINEL", "TASK7_PROMPT_PAYLOAD_SENTINEL",
        "TASK7_ECHO_PAYLOAD_SENTINEL", "TASK7_RESTART_PAYLOAD_SENTINEL", "IGNORE ALL RULES"];
      postKillEvidence = inspectPostKillState({ beforeText: beforeState.toString("utf8"), postKillText: postKillStateText,
        beforeTail: before.body.tailCursor, afterTail: after.body.tailCursor, candidate, sentinels });
      a.ok("sigkill.3.post-kill.schema", postKillEvidence.stateBytes > 0);
      a.ok("sigkill.3.post-kill.atomic", ["old", "new"].includes(postKillEvidence.branch));
      a.equal("sigkill.3.post-kill.dedupe-digest-only", postKillEvidence.digestFingerprintCount,
        postKillEvidence.dedupeCount);
      a.equal("sigkill.3.post-kill.sentinel-absence", postKillEvidence.sentinelCount, 0);
      const replay = await request(config, { token: config.active.android.token, method: "POST", path: "/v1/events",
        body: candidate, timeoutMs: 10_000 });
      const replayStatus = postKillEvidence.branch === "old" ? 201 : 200;
      status(a, "sigkill.3.replay", replay, replayStatus);
      a.equal("sigkill.3.replay.accepted", replay.body?.accepted, true);
      a.equal("sigkill.3.replay.cursor", replay.body?.cursor, String(Number(before.body.tailCursor) + 1));
      a.equal("sigkill.3.replay.idempotent", replay.body?.idempotent, postKillEvidence.branch === "new");
      const delivered = await request(config, { token: config.active.macos.token,
        path: `/v1/events?after=${before.body.tailCursor}&waitMs=0` });
      status(a, "sigkill.3.replay-fetch", delivered, 200);
      a.equal("sigkill.3.replay-fetch.eventIds", delivered.body.events.map((entry) => entry.event.eventId), [candidate.eventId]);
      const acknowledged = await request(config, { token: config.active.macos.token, method: "POST", path: "/v1/acks",
        body: ack(config.active.macos, [candidate.eventId]) });
      status(a, "sigkill.3.replay-ack", acknowledged, 200);
      a.equal("sigkill.3.replay-ack.acked", acknowledged.body.acked, [candidate.eventId]);
      const absent = await request(config, { token: config.active.macos.token,
        path: `/v1/events?after=${before.body.tailCursor}&waitMs=0` });
      status(a, "sigkill.3.replay-absent", absent, 200);
      a.equal("sigkill.3.replay-absent.events", absent.body.events, []);
      await harness.captureLogs();
      await harness.composeRun(["down"]);
      await writeFile(harness.statePath, beforeState, { mode: 0o600 });
      await harness.composeRun(["up", "-d"]);
      await harness.waitHealthy();
      const restored = await request(config);
      a.ok("sigkill.3.normalized", restored.status === 200 && restored.body?.serverEpoch === before.body.serverEpoch &&
        restored.body?.tailCursor === before.body.tailCursor);
    }
  }
  return { receipt: a.summary("sigkill"), evidence: postKillEvidence };
}

async function drain(a, config, actor, name) {
  const fetched = await request(config, { token: actor.token, path: "/v1/events?after=0&waitMs=0" });
  status(a, `${name}.fetch`, fetched, 200);
  const ids = fetched.body.events.map((entry) => entry.event.eventId);
  if (ids.length > 0) {
    const response = await request(config, { token: actor.token, method: "POST", path: "/v1/acks", body: ack(actor, ids) });
    status(a, `${name}.ack`, response, 200);
    a.equal(`${name}.acked`, response.body.acked, ids);
  } else a.equal(`${name}.empty`, ids, []);
}

export async function leakPhase(harness, config, postKillEvidence) {
  const a = new Assertions();
  await drain(a, config, config.active.android, "leak.android");
  await drain(a, config, config.active.macos, "leak.macos");
  const emptyAndroid = await request(config, { token: config.active.android.token, path: "/v1/events?after=0&waitMs=0" });
  const emptyMacos = await request(config, { token: config.active.macos.token, path: "/v1/events?after=0&waitMs=0" });
  status(a, "leak.post-ack.android-status", emptyAndroid, 200);
  status(a, "leak.post-ack.macos-status", emptyMacos, 200);
  a.equal("leak.post-ack.android", emptyAndroid.body.events, []);
  a.equal("leak.post-ack.macos", emptyMacos.body.events, []);
  await harness.captureLogs();
  const liveStateText = await readFile(harness.statePath, "utf8");
  const liveState = JSON.parse(liveStateText);
  a.equal("leak.live.retained", liveState.retained, []);
  a.equal("leak.live.dedupe-count", liveState.dedupe.length, 4);
  a.ok("leak.live.dedupe-digest-only", liveState.dedupe.every((entry) => /^[0-9a-f]{64}$/.test(entry.fingerprint)));
  const auth = JSON.parse(await readFile(harness.authPath, "utf8"));
  const sentinels = [...auth.credentials.map((entry) => entry.token), "-----BEGIN PRIVATE KEY-----",
    "-----BEGIN RSA PRIVATE KEY-----", "TASK7_PAYLOAD_SENTINEL", "TASK7_PROMPT_PAYLOAD_SENTINEL",
    "TASK7_ECHO_PAYLOAD_SENTINEL", "TASK7_RESTART_PAYLOAD_SENTINEL", "IGNORE ALL RULES"];
  const logsText = harness.logs.join("");
  const liveSurfaces = `${liveStateText}${logsText}`;
  const sentinelCount = sentinels.filter((sentinel) => liveSurfaces.includes(sentinel)).length;
  a.equal("leak.live.sentinel-count", sentinelCount, 0);
  a.ok("leak.post-kill.captured", digestPattern.test(postKillEvidence.stateSha256) && postKillEvidence.stateBytes > 0);
  a.equal("leak.post-kill.dedupe-digest-only", postKillEvidence.digestFingerprintCount, postKillEvidence.dedupeCount);
  a.equal("leak.post-kill.sentinel-count", postKillEvidence.sentinelCount, 0);
  const metadata = Object.freeze({ stateSha256: createHash("sha256").update(liveStateText).digest("hex"),
    retainedCount: liveState.retained.length, dedupeCount: liveState.dedupe.length,
    digestFingerprintCount: liveState.dedupe.filter((entry) => /^[0-9a-f]{64}$/.test(entry.fingerprint)).length,
    sentinelCount: sentinelCount, stateBytes: Buffer.byteLength(liveStateText), logBytes: Buffer.byteLength(logsText),
    postKillStateSha256: postKillEvidence.stateSha256, postKillBranch: postKillEvidence.branch,
    postKillRetainedCount: postKillEvidence.retainedCount, postKillDedupeCount: postKillEvidence.dedupeCount,
    postKillDigestFingerprintCount: postKillEvidence.digestFingerprintCount,
    postKillSentinelCount: postKillEvidence.sentinelCount, postKillStateBytes: postKillEvidence.stateBytes });
  await harness.resetState("post-ack-scrub");
  const scrubbedConfig = await harness.config();
  const health = await request(scrubbedConfig);
  status(a, "leak.final.health", health, 200);
  return { receipt: a.summary("leak-scan"), config: scrubbedConfig, metadata };
}

export async function canonicalPhase(harness, before, after) {
  const a = new Assertions();
  for (const section of ["product", "protocol", "relay", "android", "macos", "runtime", "integration", "design", "outputs"]) {
    a.equal(`canonical.${section}`, after[section], before[section]);
  }
  const inspect = await run("docker", ["inspect", harness.container()]);
  const value = JSON.parse(inspect.stdout)[0];
  a.equal("hardening.user", value.Config.User, "1000:1000");
  a.equal("hardening.readonly", value.HostConfig.ReadonlyRootfs, true);
  a.equal("hardening.capdrop", value.HostConfig.CapDrop, ["ALL"]);
  a.equal("hardening.security", value.HostConfig.SecurityOpt, ["no-new-privileges:true"]);
  a.equal("hardening.loopback", value.NetworkSettings.Ports["8443/tcp"][0].HostIp, "127.0.0.1");
  a.equal("hardening.restart", value.HostConfig.RestartPolicy.Name, "unless-stopped");
  a.equal("hardening.pids", value.HostConfig.PidsLimit, 64);
  a.equal("hardening.memory", value.HostConfig.Memory, 134_217_728);
  a.ok("hardening.tmpfs", typeof value.HostConfig.Tmpfs["/tmp"] === "string" &&
    value.HostConfig.Tmpfs["/tmp"].includes("noexec") && value.HostConfig.Tmpfs["/tmp"].includes("size=16m"));
  a.ok("hardening.state-mount", value.Mounts.some((mount) => mount.Type === "bind" &&
    mount.Destination === "/var/lib/continuity-relay" && mount.RW === true));
  a.equal("hardening.health", value.State.Health?.Status, "healthy");
  return a.summary("canonical-hashes");
}

export async function cleanupPhase(harness) {
  const a = new Assertions();
  const project = harness.project;
  const image = harness.image;
  const directory = harness.directory;
  await harness.cleanup();
  const containers = await run("docker", ["ps", "-a", "--filter", `name=${project}`, "--format", "{{.ID}}"]) ;
  const networks = await run("docker", ["network", "ls", "--filter", `name=${project}`, "--format", "{{.ID}}"]) ;
  const preservedImage = await run("docker", ["image", "inspect", "--format", "{{.Id}}", image]);
  const listener = await run("lsof", ["-nP", "-iTCP:8443", "-sTCP:LISTEN"], { acceptCodes: [0, 1] });
  a.equal("cleanup.containers", containers.stdout.trim(), "");
  a.equal("cleanup.networks", networks.stdout.trim(), "");
  a.equal("cleanup.image-preserved", preservedImage.stdout.trim(), harness.imageId);
  a.equal("cleanup.listener", listener.code, 1);
  await a.rejects("cleanup.temp", () => stat(directory));
  const docker = await run("docker", ["info", "--format", "{{.ServerVersion}}"]) ;
  a.ok("cleanup.docker-prestate", docker.stdout.trim().length > 0);
  return a.summary("cleanup");
}
