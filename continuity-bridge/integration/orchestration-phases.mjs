import { randomBytes } from "node:crypto";
import { readFile, writeFile } from "node:fs/promises";
import { Assertions, accepted, status } from "./assertions.mjs";
import { ack, event, request } from "./http-client.mjs";
import { run } from "./process-runner.mjs";

const note = (body) => ({ notificationKey: "task7-restart", packageName: "com.example.task7", appLabel: "Task 7",
  title: "Recovery", body });

export function isFailedClosedContainerState(state) {
  const value = state.State ?? state;
  const restartCount = Number(state.RestartCount ?? value.RestartCount ?? 0);
  if (value.Running === false && value.ExitCode === 1) return true;
  if (value.Restarting === true && restartCount > 0) return true;
  return value.Running === true && restartCount > 0 && value.Health?.Status !== "healthy";
}

async function waitFailedClosed(harness) {
  for (let attempt = 0; attempt < 20; attempt += 1) {
    const result = await run("docker", ["inspect", "-f", "{{json .}}", harness.container()],
      { acceptCodes: [0, 1] });
    if (result.code === 0 && isFailedClosedContainerState(JSON.parse(result.stdout))) return true;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  return false;
}

async function fetch(config, actor) {
  return request(config, { token: actor.token, path: "/v1/events?after=0&waitMs=0" });
}

async function acknowledge(config, actor, eventIds) {
  return request(config, { token: actor.token, method: "POST", path: "/v1/acks", body: ack(actor, eventIds) });
}

export async function restartPhase(harness, config) {
  const a = new Assertions();
  const { android, macos } = config.active;
  const first = event(android, { eventId: "task7-full-restart-before", originEpoch: "task7-full-restart-a", sequence: 1,
    kind: "android.notification", payload: note("TASK7_RESTART_PAYLOAD_SENTINEL") });
  const firstResult = await request(config, { token: android.token, method: "POST", path: "/v1/events", body: first });
  accepted(a, "restart.before.publish", firstResult, 201, firstResult.body.cursor);
  const epoch = firstResult.body.serverEpoch;
  await harness.composeRun(["restart", "relay"]);
  await harness.waitHealthy();
  const afterRestart = await fetch(config, macos);
  status(a, "restart.before.fetch", afterRestart, 200);
  a.equal("restart.before.redelivered", afterRestart.body.events.map((entry) => entry.event.eventId), [first.eventId]);
  a.equal("restart.before.epoch", afterRestart.body.serverEpoch, epoch);
  const firstAck = await acknowledge(config, macos, [first.eventId]);
  status(a, "restart.before.ack", firstAck, 200);
  a.equal("restart.before.acked", firstAck.body.acked, [first.eventId]);

  const second = event(android, { eventId: "task7-full-restart-after", originEpoch: "task7-full-restart-b", sequence: 1,
    kind: "android.notification", payload: note("TASK7_RESTART_PAYLOAD_SENTINEL") });
  const secondResult = await request(config, { token: android.token, method: "POST", path: "/v1/events", body: second });
  accepted(a, "restart.after.publish", secondResult, 201, secondResult.body.cursor);
  const secondAck = await acknowledge(config, macos, [second.eventId]);
  status(a, "restart.after.ack", secondAck, 200);
  a.equal("restart.after.acked", secondAck.body.acked, [second.eventId]);
  await harness.composeRun(["restart", "relay"]);
  await harness.waitHealthy();
  const absent = await fetch(config, macos);
  status(a, "restart.after.fetch", absent, 200);
  a.equal("restart.after.absent", absent.body.events, []);
  a.equal("restart.after.cursor", absent.body.nextCursor, secondResult.body.cursor);
  return a.summary("restart");
}

export async function replacementPhase(harness, config) {
  const a = new Assertions();
  const before = await request(config);
  status(a, "replacement.before", before, 200);
  await harness.captureLogs();
  await harness.composeRun(["down"]);
  await harness.composeRun(["up", "-d"]);
  await harness.waitHealthy();
  const after = await request(config);
  status(a, "replacement.after", after, 200);
  a.equal("replacement.epoch", after.body.serverEpoch, before.body.serverEpoch);
  a.equal("replacement.cursor", after.body.tailCursor, before.body.tailCursor);
  return a.summary("replacement");
}

export async function disconnectPhase(harness, config) {
  const a = new Assertions();
  await run("docker", ["network", "disconnect", harness.network(), harness.container()]);
  await a.rejects("disconnect.health-fails", () => request(config, { timeoutMs: 500 }));
  await run("docker", ["network", "connect", harness.network(), harness.container()]);
  await harness.waitHealthy();
  const recovered = await request(config);
  status(a, "disconnect.recovered", recovered, 200);
  a.equal("disconnect.status", recovered.body.status, "ok");
  return a.summary("disconnect");
}

export async function rotationPhase(harness, config) {
  const a = new Assertions();
  const auth = JSON.parse(await readFile(harness.authPath, "utf8"));
  const old = auth.credentials.find((entry) => entry.role === "android" && entry.revoked !== true);
  const next = randomBytes(32).toString("hex");
  old.revoked = true;
  auth.credentials.push({ token: next, role: "android", deviceId: old.deviceId, revoked: false });
  await writeFile(harness.authPath, `${JSON.stringify(auth)}\n`, { mode: 0o600 });
  await harness.composeRun(["restart", "relay"]);
  await harness.waitHealthy();
  const rotated = await harness.config();
  const oldResponse = await request(rotated, { token: old.token, path: "/v1/events?after=0&waitMs=0" });
  status(a, "rotation.old", oldResponse, 401, "unauthorized");
  const nextResponse = await request(rotated, { token: next, path: "/v1/events?after=0&waitMs=0" });
  status(a, "rotation.new", nextResponse, 200);
  a.equal("rotation.new.protocol", nextResponse.body.protocolVersion, 1);
  return { receipt: a.summary("rotation"), config: rotated };
}

export async function corruptStatePhase(harness) {
  const a = new Assertions();
  const valid = await readFile(harness.statePath);
  await harness.captureLogs();
  await harness.composeRun(["down"]);
  await writeFile(harness.statePath, "{corrupt", { mode: 0o600 });
  await harness.composeRun(["up", "-d"]);
  a.equal("corrupt.fail-closed", await waitFailedClosed(harness), true);
  a.equal("corrupt.preserved", await readFile(harness.statePath, "utf8"), "{corrupt");
  await harness.composeRun(["down"]);
  await writeFile(harness.statePath, valid, { mode: 0o600 });
  await harness.composeRun(["up", "-d"]);
  await harness.waitHealthy();
  const recovered = await request(await harness.config());
  status(a, "corrupt.recovered", recovered, 200);
  return a.summary("corrupt-state");
}
