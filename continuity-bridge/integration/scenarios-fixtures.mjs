import { readFile } from "node:fs/promises";
import { Assertions, accepted, status } from "./assertions.mjs";
import { ack, request } from "./http-client.mjs";

async function fixture(name) {
  return JSON.parse(await readFile(new URL(`../protocol/fixtures/${name}`, import.meta.url), "utf8"));
}

function assignPath(target, path, value) {
  const parts = path.split(".");
  let parent = target;
  for (const part of parts.slice(0, -1)) parent = parent[part];
  parent[parts.at(-1)] = value;
}

function eventForStep(fixtures, step, scenarioIndex) {
  const value = structuredClone(fixtures[step.fixture]);
  if ([1, 6, 9].includes(scenarioIndex) && step.fixture === "android-notification.json") {
    const now = Date.now();
    value.createdAtMs = now;
    value.expiresAtMs = scenarioIndex === 6 ? now + 750 : now + 60_000;
  }
  for (const [path, replacement] of Object.entries(step.set ?? {})) assignPath(value, path, replacement);
  for (const path of step.delete ?? []) {
    const parts = path.split(".");
    let parent = value;
    for (const part of parts.slice(0, -1)) parent = parent[part];
    delete parent[parts.at(-1)];
  }
  if (step.repeat !== undefined) assignPath(value, step.repeat.path, step.repeat.value.repeat(step.repeat.count));
  return value;
}

async function executeStep(a, harness, config, fixtures, scenario, scenarioIndex, step, stepIndex) {
  const label = `fixture.case-${scenarioIndex + 1}.step-${stepIndex + 1}`;
  const actor = config.active[step.actorRole];
  if (step.op === "publish" || step.op === "publishRawFile") {
    const raw = step.op === "publishRawFile"
      ? await readFile(new URL(`../protocol/fixtures/${step.fixture}`, import.meta.url)) : undefined;
    const body = step.op === "publish" ? eventForStep(fixtures, step, scenarioIndex) : undefined;
    const response = await request(config, { token: actor?.token ?? "task7-unknown-role", method: "POST",
      path: "/v1/events", body, raw });
    if (step.expect.status === 201 || step.expect.status === 200) {
      accepted(a, label, response, step.expect.status, step.expect.cursor, step.expect.idempotent ?? false);
    } else status(a, label, response, step.expect.status, step.expect.code);
    return config;
  }
  if (step.op === "fetch") {
    const waitMs = scenarioIndex === 9 && step.waitMs === 25000 ? 5 : step.waitMs;
    const response = await request(config, { token: actor.token,
      path: `/v1/events?after=${step.after}&waitMs=${waitMs}` });
    status(a, label, response, step.expect.status);
    a.equal(`${label}.eventIds`, response.body.events.map((entry) => entry.event.eventId), step.expect.eventIds);
    a.equal(`${label}.nextCursor`, response.body.nextCursor, step.expect.nextCursor);
    if (step.expect.payloadText !== undefined) {
      a.equal(`${label}.payloadText`, response.body.events[0].event.payload.text, step.expect.payloadText);
    }
    return config;
  }
  if (step.op === "applyAndMaybeAck") {
    if (!step.surfaceSucceeded || !step.appliedIdPersisted) {
      a.equal(`${label}.withheld`, step.expect.ackWithheld, true);
      return config;
    }
    const response = await request(config, { token: actor.token, method: "POST", path: "/v1/acks",
      body: ack(actor, step.eventIds) });
    status(a, label, response, step.expect.status);
    a.equal(`${label}.acked`, response.body.acked, step.expect.acked);
    return config;
  }
  if (step.op === "advanceMs") {
    if (scenarioIndex === 6 && stepIndex === 3) await new Promise((resolve) => setTimeout(resolve, 900));
    a.equal(`${label}.adapter`, step.expect.status, 0);
    return config;
  }
  if (step.op === "restart") {
    const before = await request(config);
    status(a, `${label}.before`, before, 200);
    await harness.composeRun(["restart", "relay"]);
    await harness.waitHealthy();
    const after = await request(config);
    status(a, `${label}.after`, after, 200);
    a.equal(`${label}.serverEpoch`, after.body.serverEpoch, before.body.serverEpoch);
    a.equal(`${label}.tailCursor`, after.body.tailCursor, step.expect.tailCursor);
    return config;
  }
  if (step.op === "publishNotificationSeries") {
    const base = fixtures["android-notification.json"];
    for (let index = 1; index <= step.count; index += 1) {
      const candidate = structuredClone(base);
      const now = Date.now();
      Object.assign(candidate, { eventId: `series-note-${String(index).padStart(3, "0")}`,
        originEpoch: `fixture-series-${scenarioIndex}`, sequence: index, createdAtMs: now, expiresAtMs: now + 60_000 });
      candidate.payload.notificationKey = `series-${index}`;
      if (step.bodyBytes !== undefined) candidate.payload.body = "b".repeat(step.bodyBytes);
      const response = await request(config, { token: config.active.android.token, method: "POST", path: "/v1/events",
        body: candidate });
      status(a, `${label}.publish`, response, step.expect.status);
    }
    const retained = await request(config, { token: config.active.macos.token, path: "/v1/events?after=0&waitMs=0" });
    status(a, `${label}.fetch`, retained, 200);
    a.equal(`${label}.cursor`, retained.body.nextCursor, step.expect.cursor);
    if (step.expect.retainedNotifications !== undefined) {
      a.equal(`${label}.retained`, retained.body.events.length, step.expect.retainedNotifications);
      a.equal(`${label}.first`, retained.body.events[0].event.eventId, step.expect.firstRetainedEventId);
    }
    if (step.expect.retainedNotificationBytesAtMost !== undefined) {
      const bytes = retained.body.events.reduce((sum, entry) => sum + Buffer.byteLength(JSON.stringify(entry.event)), 0);
      a.ok(`${label}.bytes`, bytes <= step.expect.retainedNotificationBytesAtMost);
      a.ok(`${label}.evicted`, retained.body.events.length <= step.count - step.expect.evictedAtLeast);
    }
    return config;
  }
  throw new Error(`unsupported canonical fixture operation: ${scenario.name}:${step.op}`);
}

export async function runFixtures(harness, initialConfig) {
  const a = new Assertions();
  const cases = await fixture("contract-cases.json");
  const fixtures = { "android-clipboard.json": await fixture("android-clipboard.json"),
    "macos-clipboard.json": await fixture("macos-clipboard.json"),
    "android-notification.json": await fixture("android-notification.json") };
  let config = initialConfig;
  for (const [scenarioIndex, scenario] of cases.scenarios.entries()) {
    if (scenarioIndex > 0) config = await harness.resetState(`fixture-${scenarioIndex + 1}`);
    for (const [stepIndex, step] of scenario.steps.entries()) {
      config = await executeStep(a, harness, config, fixtures, scenario, scenarioIndex, step, stepIndex);
    }
    a.equal(`fixture.case-${scenarioIndex + 1}.executed`, true, true);
  }
  a.equal("fixture.schema", cases.schemaVersion, 1);
  a.equal("fixture.scenario-count", cases.scenarios.length, 12);
  return a.summary("fixtures");
}
