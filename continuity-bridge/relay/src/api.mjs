import { errorBody, RelayError } from "./errors.mjs";
import { createHash } from "node:crypto";
import { LIMITS, parseAck, parseEvent, parseQuery } from "./schema.mjs";
import { parseStrictJson } from "./strict-json.mjs";

const EVENT_ROLES = new Set(["android", "macos"]);
const EVENT_KINDS = new Set(["clipboard.text", "android.notification"]);

function send(response, status, body) {
  if (response.destroyed || response.writableEnded) return;
  const encoded = Buffer.from(JSON.stringify(body), "utf8");
  response.writeHead(status, { "content-type": "application/json; charset=utf-8", "content-length": encoded.length });
  response.end(encoded);
}

function sendFailure(response, result) { send(response, result.status, errorBody(result.code)); }

function deviceIdDigest(value) { return createHash("sha256").update(value, "utf8").digest("hex"); }

function rejectionMetadata(path, actor, result, value) {
  const fields = { method: "POST", path, status: result.status, errorCode: result.code, role: actor.role,
    actorDeviceIdSha256: deviceIdDigest(actor.deviceId) };
  const bodyDeviceId = value?.[path === "/v1/events" ? "originDeviceId" : "recipientDeviceId"];
  if (typeof bodyDeviceId === "string") {
    fields.bodyDeviceIdSha256 = deviceIdDigest(bodyDeviceId);
    fields.identityMatch = actor.deviceId === bodyDeviceId;
  }
  if (path === "/v1/events") {
    if (EVENT_ROLES.has(value?.originRole)) fields.originRole = value.originRole;
    if (EVENT_KINDS.has(value?.kind)) fields.kind = value.kind;
  }
  return fields;
}

function rejectRequest(response, logger, path, actor, result, value) {
  logger.warn("request.rejected", rejectionMetadata(path, actor, result, value));
  return sendFailure(response, result);
}

function readBody(request, limit) {
  return new Promise((resolveBody, rejectBody) => {
    const chunks = [];
    let length = 0;
    let oversized = false;
    request.on("data", (chunk) => {
      length += chunk.length;
      if (length > limit) oversized = true;
      else chunks.push(chunk);
    });
    request.on("end", () => oversized ? rejectBody(new RelayError("payload_too_large")) : resolveBody(Buffer.concat(chunks)));
    request.on("error", rejectBody);
  });
}

function publicFetch(result) {
  return { protocolVersion: 1, serverEpoch: result.serverEpoch, after: result.after, nextCursor: result.nextCursor,
    events: result.events.map((entry) => ({ cursor: entry.cursor, event: entry.event })) };
}

function authenticate(request, response, authenticator, logger) {
  const actor = authenticator.authenticate(request.headers.authorization);
  if (actor.status !== undefined) {
    logger.warn("auth.rejected", { status: actor.status });
    sendFailure(response, actor);
    return undefined;
  }
  return actor;
}

async function publish(request, response, context, actor) {
  let value;
  try {
    const body = await readBody(request, LIMITS.eventBody);
    value = parseStrictJson(body);
    const parsed = parseEvent(actor, value);
    if (parsed.status !== 0) return rejectRequest(response, context.logger, "/v1/events", actor, parsed, value);
    const result = await context.store.publish(actor, parsed.event);
    if (result.status >= 400) return rejectRequest(response, context.logger, "/v1/events", actor, result, value);
    context.logger.info("event.accepted", { eventId: parsed.event.eventId, role: actor.role, kind: parsed.event.kind,
      bytes: body.length, cursor: result.cursor, status: result.status });
    return send(response, result.status, { accepted: true, eventId: parsed.event.eventId, cursor: result.cursor,
      serverEpoch: context.store.serverEpoch, idempotent: result.idempotent });
  } catch (error) {
    if (error instanceof RelayError) return rejectRequest(response, context.logger, "/v1/events", actor, error, value);
    if (error instanceof SyntaxError || error instanceof TypeError) {
      return rejectRequest(response, context.logger, "/v1/events", actor, { status: 400, code: "malformed_json" }, value);
    }
    throw error;
  }
}

async function fetchEvents(request, response, context, actor, url) {
  const query = parseQuery(url.searchParams);
  if (query.status !== 0) return sendFailure(response, query);
  const controller = new AbortController();
  const cancel = () => { if (!response.writableEnded) controller.abort(); };
  request.once("aborted", cancel);
  response.once("close", cancel);
  const result = await context.store.waitForEvents(actor, query.after, query.waitMs, controller.signal);
  request.removeListener("aborted", cancel);
  response.removeListener("close", cancel);
  return send(response, 200, publicFetch(result));
}

async function acknowledge(request, response, context, actor) {
  let value;
  try {
    const body = await readBody(request, LIMITS.ackBody);
    value = parseStrictJson(body);
    const parsed = parseAck(actor, value);
    if (parsed.status !== 0) return rejectRequest(response, context.logger, "/v1/acks", actor, parsed, value);
    const result = await context.store.ack(actor, parsed.eventIds);
    if (result.status >= 400) return rejectRequest(response, context.logger, "/v1/acks", actor, result, value);
    context.logger.info("ack.accepted", { role: actor.role, status: 200, bytes: body.length });
    return send(response, 200, { acked: result.acked, alreadyAbsent: result.alreadyAbsent });
  } catch (error) {
    if (error instanceof RelayError) return rejectRequest(response, context.logger, "/v1/acks", actor, error, value);
    if (error instanceof SyntaxError || error instanceof TypeError) {
      return rejectRequest(response, context.logger, "/v1/acks", actor, { status: 400, code: "malformed_json" }, value);
    }
    throw error;
  }
}

export function createHandler(context) {
  return async (request, response) => {
    try {
      const url = new URL(request.url, "https://localhost");
      if (request.method === "GET" && url.pathname === "/v1/health") {
        return send(response, 200, { protocolVersion: 1, status: "ok", serverEpoch: context.store.serverEpoch,
          tailCursor: context.store.tailCursor });
      }
      if (!["/v1/events", "/v1/acks"].includes(url.pathname)) return send(response, 404, { error: { code: "not_found", message: "Not found." } });
      const actor = authenticate(request, response, context.authenticator, context.logger);
      if (actor === undefined) return;
      if (request.method === "POST" && url.pathname === "/v1/events") return await publish(request, response, context, actor);
      if (request.method === "GET" && url.pathname === "/v1/events") return await fetchEvents(request, response, context, actor, url);
      if (request.method === "POST" && url.pathname === "/v1/acks") return await acknowledge(request, response, context, actor);
      return send(response, 404, { error: { code: "not_found", message: "Not found." } });
    } catch (error) {
      if (error instanceof RelayError) return sendFailure(response, error);
      if (error instanceof SyntaxError || error instanceof TypeError) return sendFailure(response, { status: 400, code: "malformed_json" });
      context.logger.error("state.failure", error, { status: 500 });
      return sendFailure(response, { status: 500, code: "state_error" });
    }
  };
}
