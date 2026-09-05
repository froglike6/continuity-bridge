import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { randomBytes } from "node:crypto";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import https from "node:https";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

export const ANDROID = Object.freeze({ role: "android", deviceId: "device-android-test" });
export const MACOS = Object.freeze({ role: "macos", deviceId: "device-macos-test" });

export function clipboard(overrides = {}) {
  return {
    protocolVersion: 1,
    eventId: "event-clipboard-1",
    originDeviceId: ANDROID.deviceId,
    originRole: ANDROID.role,
    originEpoch: "epoch-android-a",
    sequence: 1,
    kind: "clipboard.text",
    createdAtMs: 1_700_000_000_000,
    payload: { text: "HARMLESS_CLIPBOARD" },
    ...structuredClone(overrides),
  };
}

export function notification(sequence, overrides = {}) {
  return {
    protocolVersion: 1,
    eventId: `event-notification-${sequence}`,
    originDeviceId: ANDROID.deviceId,
    originRole: ANDROID.role,
    originEpoch: "epoch-android-a",
    sequence,
    kind: "android.notification",
    createdAtMs: 1_700_000_000_000,
    payload: {
      notificationKey: `key-${sequence}`,
      packageName: "com.example.harmless",
      appLabel: "Harmless",
      title: "Title",
      body: "Body",
    },
    ...structuredClone(overrides),
  };
}

export async function tempState() {
  const directory = await mkdtemp(join(tmpdir(), "continuity-relay-test-"));
  return { directory, path: join(directory, "state.json") };
}

export function fixedClock(initial = 1_700_000_000_000) {
  let value = initial;
  return Object.freeze({ now: () => value, advance: (milliseconds) => { value += milliseconds; } });
}

export async function makeTls(directory) {
  const key = join(directory, "server-key.pem");
  const cert = join(directory, "server-cert.pem");
  execFileSync("openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "1",
    "-subj", "/CN=localhost", "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1",
    "-keyout", key, "-out", cert], { stdio: "ignore" });
  return { key, cert, ca: await readFile(cert) };
}

export function request({ port, ca, token, method = "GET", path = "/v1/health", body }) {
  return new Promise((resolveRequest, rejectRequest) => {
    const headers = {};
    if (token !== undefined) headers.authorization = `Bearer ${token}`;
    if (body !== undefined) headers["content-type"] = "application/json";
    const req = https.request({ hostname: "localhost", port, path, method, ca, headers }, (res) => {
      const chunks = [];
      res.on("data", (chunk) => chunks.push(chunk));
      res.on("end", () => resolveRequest({ status: res.statusCode, body: JSON.parse(Buffer.concat(chunks).toString("utf8")) }));
    });
    req.on("error", rejectRequest);
    if (body !== undefined) req.end(typeof body === "string" ? body : JSON.stringify(body));
    else req.end();
  });
}

export async function startProcess() {
  const fixture = await tempState();
  const tls = await makeTls(fixture.directory);
  const authPath = join(fixture.directory, "auth.json");
  const tokens = Object.freeze({ android: randomBytes(32).toString("hex"), macos: randomBytes(32).toString("hex"),
    revoked: randomBytes(32).toString("hex") });
  await writeFile(authPath, JSON.stringify({ credentials: [
    { token: tokens.android, ...ANDROID, revoked: false },
    { token: tokens.macos, ...MACOS, revoked: false },
    { token: tokens.revoked, role: "android", deviceId: "revoked-device", revoked: true },
  ] }), { mode: 0o600 });
  const child = spawn(process.execPath, [fileURLToPath(new URL("../src/main.mjs", import.meta.url))], {
    env: { ...process.env, RELAY_HOST: "127.0.0.1", RELAY_PORT: "0", RELAY_STATE_PATH: fixture.path,
      RELAY_AUTH_PATH: authPath, RELAY_TLS_CERT_PATH: tls.cert, RELAY_TLS_KEY_PATH: tls.key },
    stdio: ["ignore", "pipe", "pipe"],
  });
  const line = await new Promise((resolveLine, rejectLine) => {
    let text = "";
    const timer = setTimeout(() => rejectLine(new Error("relay start timeout")), 5_000);
    child.stdout.on("data", (chunk) => {
      text += chunk;
      const newline = text.indexOf("\n");
      if (newline >= 0) { clearTimeout(timer); resolveLine(text.slice(0, newline)); }
    });
    child.once("exit", (code) => { clearTimeout(timer); rejectLine(new Error(`relay exited ${code}`)); });
  });
  const ready = JSON.parse(line);
  assert.equal(ready.event, "server.ready");
  return { child, fixture, tls, tokens, port: ready.port };
}

export async function stopProcess(child) {
  child.kill("SIGTERM");
  await new Promise((resolveExit) => child.once("exit", resolveExit));
}
