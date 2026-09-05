import assert from "node:assert/strict";
import https from "node:https";
import { readFile } from "node:fs/promises";

export async function loadConfig(paths = {}) {
  const baseUrl = new URL(paths.url ?? process.env.RELAY_URL ?? "https://localhost:8443");
  const ca = await readFile(paths.caPath ?? required("RELAY_CA_PATH"));
  const auth = JSON.parse(await readFile(paths.authPath ?? required("RELAY_AUTH_PATH"), "utf8"));
  assert.ok(Array.isArray(auth.credentials), "auth credentials must be an array");
  const active = Object.fromEntries(auth.credentials.filter((entry) => entry.revoked !== true)
    .map((entry) => [entry.role, Object.freeze({ token: entry.token, role: entry.role, deviceId: entry.deviceId })]));
  const revoked = auth.credentials.find((entry) => entry.revoked === true)?.token;
  assert.ok(active.android && active.macos && revoked, "auth requires active roles and a revoked credential");
  return Object.freeze({ baseUrl, ca, active: Object.freeze(active), revoked });
}

function required(name) {
  const value = process.env[name];
  if (typeof value !== "string" || value.length === 0) throw new Error(`missing ${name}`);
  return value;
}

export function request(config, options = {}) {
  const path = options.path ?? "/v1/health";
  const body = options.raw ?? (options.body === undefined ? undefined : JSON.stringify(options.body));
  const headers = { accept: "application/json" };
  if (options.token !== undefined) headers.authorization = `Bearer ${options.token}`;
  if (body !== undefined) {
    headers["content-type"] = "application/json; charset=utf-8";
    headers["content-length"] = Buffer.byteLength(body);
  }
  const signal = AbortSignal.timeout(options.timeoutMs ?? 8_000);
  return new Promise((resolve, reject) => {
    const req = https.request({ hostname: config.baseUrl.hostname, port: config.baseUrl.port,
      servername: options.servername ?? config.baseUrl.hostname, method: options.method ?? "GET", path,
      ca: options.ca ?? config.ca, rejectUnauthorized: true, headers, signal }, (response) => {
      const chunks = [];
      response.on("data", (chunk) => chunks.push(chunk));
      response.on("end", () => {
        const text = Buffer.concat(chunks).toString("utf8");
        let parsed;
        try { parsed = text.length === 0 ? null : JSON.parse(text); }
        catch (error) { reject(new Error("non-JSON relay response", { cause: error })); return; }
        resolve(Object.freeze({ status: response.statusCode, body: parsed }));
      });
    });
    req.once("error", reject);
    if (body === undefined) req.end();
    else req.end(body);
  });
}

export function heldRequest(config, actor, after, waitMs = 25_000) {
  const controller = new AbortController();
  const promise = new Promise((resolve, reject) => {
    const req = https.request({ hostname: config.baseUrl.hostname, port: config.baseUrl.port,
      servername: config.baseUrl.hostname, method: "GET", path: `/v1/events?after=${after}&waitMs=${waitMs}`,
      ca: config.ca, rejectUnauthorized: true, headers: { authorization: `Bearer ${actor.token}` },
      signal: controller.signal }, (response) => {
      response.resume();
      response.once("end", () => resolve(response.statusCode));
    });
    req.once("error", reject);
    req.end();
  });
  return Object.freeze({ promise, cancel: () => controller.abort() });
}

export class RoleConnection {
  constructor(config, actor) { this.config = config; this.actor = actor; this.active = false; }

  hold(after) {
    if (this.active) throw new Error("role already has a live connection");
    this.active = true;
    const held = heldRequest(this.config, this.actor, after);
    held.promise.then(() => { this.active = false; }, () => { this.active = false; });
    return held;
  }
}

export function event(actor, values) {
  return Object.freeze({ protocolVersion: 1, eventId: values.eventId, originDeviceId: actor.deviceId,
    originRole: actor.role, originEpoch: values.originEpoch, sequence: values.sequence,
    kind: values.kind ?? "clipboard.text", createdAtMs: values.createdAtMs ?? 1_800_000_000_000,
    ...(values.expiresAtMs === undefined ? {} : { expiresAtMs: values.expiresAtMs }), payload: values.payload });
}

export function ack(actor, eventIds) {
  return { protocolVersion: 1, recipientDeviceId: actor.deviceId, recipientRole: actor.role, eventIds };
}
