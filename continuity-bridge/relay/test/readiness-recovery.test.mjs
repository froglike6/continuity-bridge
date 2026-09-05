import assert from "node:assert/strict";
import { readFile, rm } from "node:fs/promises";
import test from "node:test";
import { Authenticator } from "../src/auth.mjs";
import { StateError } from "../src/errors.mjs";
import { MetadataLogger } from "../src/logger.mjs";
import { atomicWrite } from "../src/persistence.mjs";
import { createRelayServer, close, listen } from "../src/server.mjs";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, makeTls, request, tempState } from "./helpers.mjs";

test("Given a pre-commit persistence failure When readiness retries after storage recovers Then the live relay reloads safely", async (context) => {
  // Given
  const fixture = await tempState();
  const tls = await makeTls(fixture.directory);
  let storageAvailable = true;
  const store = await DurableStore.open({ statePath: fixture.path, writeState: async (path, state) => {
    if (!storageAvailable) throw new StateError(new Error("injected_pre_commit_failure"));
    await atomicWrite(path, state);
  } });
  const tokens = Object.freeze({ android: "ANDROID_READINESS_TOKEN", macos: "MACOS_READINESS_TOKEN" });
  const authenticator = new Authenticator([
    { token: tokens.android, ...ANDROID, revoked: false },
    { token: tokens.macos, ...MACOS, revoked: false },
  ]);
  const logger = new MetadataLogger({ write: () => undefined });
  const server = createRelayServer({
    tls: { cert: await readFile(tls.cert), key: await readFile(tls.key), minVersion: "TLSv1.2" },
    store,
    authenticator,
    logger,
  });
  context.after(async () => {
    await close(server);
    await rm(fixture.directory, { recursive: true, force: true });
  });
  const address = await listen(server, "127.0.0.1", 0);

  // When
  storageAvailable = false;
  const failedPublish = await request({ port: address.port, ca: tls.ca, token: tokens.android, method: "POST",
    path: "/v1/events", body: clipboard() });
  const liveness = await request({ port: address.port, ca: tls.ca, path: "/v1/health" });
  const unavailable = await request({ port: address.port, ca: tls.ca, path: "/v1/ready" });
  storageAvailable = true;
  const ready = await request({ port: address.port, ca: tls.ca, path: "/v1/ready" });
  const retry = await request({ port: address.port, ca: tls.ca, token: tokens.android, method: "POST",
    path: "/v1/events", body: clipboard() });
  const fetched = await request({ port: address.port, ca: tls.ca, token: tokens.macos,
    path: "/v1/events?after=0&waitMs=0" });

  // Then
  assert.deepEqual([failedPublish.status, failedPublish.body.error.code], [500, "state_error"]);
  assert.deepEqual([liveness.status, liveness.body.status], [200, "ok"]);
  assert.deepEqual([unavailable.status, unavailable.body.error.code], [503, "state_error"]);
  assert.deepEqual([ready.status, ready.body.status, ready.body.tailCursor], [200, "ready", "0"]);
  assert.deepEqual([retry.status, retry.body.idempotent, retry.body.cursor], [201, false, "1"]);
  assert.deepEqual(fetched.body.events.map((entry) => entry.event.eventId), ["event-clipboard-1"]);
});

test("Given a post-write uncertain failure When the store recovers and the event is retried Then disk truth wins without a duplicate", async (context) => {
  // Given
  const fixture = await tempState();
  context.after(() => rm(fixture.directory, { recursive: true, force: true }));
  let writeOutcomeCertain = true;
  const store = await DurableStore.open({ statePath: fixture.path, writeState: async (path, state) => {
    await atomicWrite(path, state);
    if (!writeOutcomeCertain) throw new StateError(new Error("injected_post_write_uncertainty"));
  } });

  // When
  writeOutcomeCertain = false;
  await assert.rejects(() => store.publish(ANDROID, clipboard()), { name: "StateError" });
  writeOutcomeCertain = true;
  const recovered = await store.recover();
  const retry = await store.publish(ANDROID, clipboard());
  const fetched = await store.fetch(MACOS, "0");
  const persisted = JSON.parse(await readFile(fixture.path, "utf8"));

  // Then
  assert.equal(recovered, true);
  assert.deepEqual([retry.status, retry.idempotent, retry.cursor], [200, true, "1"]);
  assert.deepEqual(fetched.events.map((entry) => entry.event.eventId), ["event-clipboard-1"]);
  assert.deepEqual(persisted.retained.map((entry) => entry.event.eventId), ["event-clipboard-1"]);
});
