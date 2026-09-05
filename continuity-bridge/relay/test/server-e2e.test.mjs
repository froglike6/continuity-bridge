import assert from "node:assert/strict";
import test from "node:test";
import { clipboard, request, startProcess, stopProcess } from "./helpers.mjs";

test("Given a live HTTPS relay process When publish fetch ack Then observable protocol state changes", async (context) => {
  // Given
  const runtime = await startProcess();
  context.after(() => stopProcess(runtime.child));
  // When
  const health = await request({ port: runtime.port, ca: runtime.tls.ca });
  const published = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android, method: "POST", path: "/v1/events", body: clipboard() });
  const fetched = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.macos, path: "/v1/events?after=0&waitMs=0" });
  const acked = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.macos, method: "POST", path: "/v1/acks", body: {
    protocolVersion: 1, recipientDeviceId: "device-macos-test", recipientRole: "macos", eventIds: ["event-clipboard-1"],
  } });
  const empty = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.macos, path: "/v1/events?after=0&waitMs=0" });
  // Then
  assert.equal(health.status, 200);
  assert.deepEqual([published.status, published.body.cursor], [201, "1"]);
  assert.equal(fetched.body.events[0].event.eventId, "event-clipboard-1");
  assert.deepEqual(acked.body.acked, ["event-clipboard-1"]);
  assert.deepEqual(empty.body.events, []);
});

test("Given a live HTTPS relay process When requests are unauthorized malformed and forbidden Then exact errors are returned", async (context) => {
  // Given
  const runtime = await startProcess();
  context.after(() => stopProcess(runtime.child));
  // When
  const missing = await request({ port: runtime.port, ca: runtime.tls.ca, method: "POST", path: "/v1/events", body: "{" });
  const malformed = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.android, method: "POST", path: "/v1/events", body: "{" });
  const forbidden = await request({ port: runtime.port, ca: runtime.tls.ca, token: runtime.tokens.macos, method: "POST", path: "/v1/events", body: {
    ...clipboard(), originRole: "macos", originDeviceId: "device-macos-test", kind: "android.notification",
    payload: { notificationKey: "k", packageName: "p", appLabel: "a", title: "t", body: "b" },
  } });
  // Then
  assert.deepEqual([missing.status, missing.body.error.code], [401, "unauthorized"]);
  assert.deepEqual([malformed.status, malformed.body.error.code], [400, "malformed_json"]);
  assert.deepEqual([forbidden.status, forbidden.body.error.code], [403, "direction_forbidden"]);
});
