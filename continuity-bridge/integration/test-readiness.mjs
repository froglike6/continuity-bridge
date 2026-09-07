import assert from "node:assert/strict";
import childProcess from "node:child_process";
import { EventEmitter } from "node:events";
import https from "node:https";
import { syncBuiltinESMExports } from "node:module";
import test from "node:test";
import { runInNewContext } from "node:vm";
import { DockerHarness } from "./local-docker.mjs";

function harness() {
  const value = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: `sha256:${"a".repeat(64)}` });
  value.runtime = "/tmp/task7-readiness-fixture";
  value.caPath = `${value.runtime}/tls/ca.pem`;
  value.authPath = `${value.runtime}/auth/auth.json`;
  value.config = async () => ({ baseUrl: new URL("https://localhost:8443"), ca: "fixture-ca" });
  return value;
}

function stubBoundaries(context, responseFor) {
  const paths = [];
  context.mock.method(childProcess, "spawn", (command, args) => {
    assert.equal(command, "docker");
    assert.equal(args[0], "inspect");
    const child = new EventEmitter();
    child.stdout = new EventEmitter();
    child.stderr = new EventEmitter();
    queueMicrotask(() => {
      child.stdout.emit("data", Buffer.from("true\n"));
      child.emit("exit", 0, null);
    });
    return child;
  });
  syncBuiltinESMExports();
  context.after(() => { context.mock.restoreAll(); syncBuiltinESMExports(); });
  context.mock.method(https, "request", (options, callback) => {
    paths.push(options.path);
    const request = new EventEmitter();
    request.end = () => queueMicrotask(() => {
      const reply = responseFor(options.path, paths.length);
      const response = new EventEmitter();
      response.statusCode = reply.status;
      callback(response);
      response.emit("data", Buffer.from(JSON.stringify(reply.body)));
      response.emit("end");
    });
    return request;
  });
  return paths;
}

const ready = { status: 200, body: { protocolVersion: 1, status: "ready", serverEpoch: "a".repeat(32), tailCursor: "0" } };
const live = { status: 200, body: { protocolVersion: 1, status: "ok", serverEpoch: "a".repeat(32), tailCursor: "0" } };
const unavailable = { status: 503, body: { error: { code: "state_error" } } };

test("Given a live process with unavailable storage When startup waits Then liveness cannot pass readiness", async (context) => {
  const paths = stubBoundaries(context, (path) => path === "/v1/ready" ? unavailable : live);
  await assert.rejects(() => harness().waitHealthy(), { message: "relay readiness deadline exceeded" });
  assert.equal(paths.length, 20);
  assert.ok(paths.every((path) => path === "/v1/ready"));
});

test("Given storage recovery When startup waits Then only a successful ready response releases the gate", async (context) => {
  const responses = [unavailable, live, { ...ready, status: 503 }, ready];
  const paths = stubBoundaries(context, (path, count) => path === "/v1/ready" ? responses[count - 1] : live);
  await harness().waitHealthy();
  assert.deepEqual(paths, Array(4).fill("/v1/ready"));
});

test("Given generated Compose healthcheck When executed Then it probes durable readiness and propagates failure", () => {
  const value = harness();
  const command = JSON.parse(value.compose().match(/^\s+test: (.+)$/m)[1]);
  assert.deepEqual(command.slice(0, 3), ["CMD", "node", "-e"]);
  for (const statusCode of [200, 503]) {
    let observedPath;
    let exitCode;
    runInNewContext(command[3], {
      require: (name) => name === "fs" ? { readFileSync: () => "fixture-ca" } : {
        get: (options, callback) => {
          observedPath = options.path;
          callback({ statusCode });
          return { on: () => undefined };
        },
      },
      process: { exit: (code) => { exitCode = code; } },
    });
    assert.equal(observedPath, "/v1/ready");
    assert.equal(exitCode, statusCode === 200 ? 0 : 1);
  }
});
