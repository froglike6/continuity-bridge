import assert from "node:assert/strict";
import https from "node:https";
import test from "node:test";
import { DurableStore } from "../src/store.mjs";
import { clipboard, fixedClock, startProcess } from "./helpers.mjs";

test("Given a live process persisting a maximum clipboard When SIGKILL follows request completion Then state is valid old-or-new", async () => {
  // Given
  const runtime = await startProcess();
  const body = Buffer.from(JSON.stringify(clipboard({ eventId: "crash-event", payload: { text: "x".repeat(1_048_576) } })));
  const exited = new Promise((resolveExit) => runtime.child.once("exit", resolveExit));
  // When
  const requestFinished = new Promise((resolveRequest) => {
    const request = https.request({ hostname: "localhost", port: runtime.port, path: "/v1/events", method: "POST", ca: runtime.tls.ca,
      headers: { authorization: `Bearer ${runtime.tokens.android}`, "content-type": "application/json", "content-length": body.length } }, (response) => {
      response.resume();
      response.once("end", resolveRequest);
    });
    request.once("error", resolveRequest);
    request.once("finish", () => { runtime.child.kill("SIGKILL"); resolveRequest(); });
    request.end(body);
  });
  await requestFinished;
  await Promise.race([exited, new Promise((_, reject) => setTimeout(() => reject(new Error("SIGKILL timeout")), 2_000))]);
  const recovered = await DurableStore.open({ statePath: runtime.fixture.path, clock: fixedClock() });
  // Then
  assert.ok(["0", "1"].includes(recovered.tailCursor));
  assert.match(recovered.serverEpoch, /^[0-9a-f]{32}$/);
});
