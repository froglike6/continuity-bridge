const ALLOWED = new Set(["eventId", "role", "kind", "bytes", "cursor", "status", "errorClass", "port", "method", "path",
  "errorCode", "actorDeviceIdSha256", "bodyDeviceIdSha256", "identityMatch", "originRole"]);
const ALLOWED_BOOLEANS = new Set(["identityMatch"]);

function metadata(fields) {
  const safe = {};
  for (const [key, value] of Object.entries(fields)) {
    if (ALLOWED.has(key) && (["string", "number"].includes(typeof value) || ALLOWED_BOOLEANS.has(key) && typeof value === "boolean")) safe[key] = value;
  }
  return safe;
}

export class MetadataLogger {
  constructor(sink = process.stdout) { this.sink = sink; }

  write(level, event, fields) {
    this.sink.write(`${JSON.stringify({ level, event, ...metadata(fields) })}\n`);
  }

  info(event, fields = {}) { this.write("info", event, fields); }

  warn(event, fields = {}) { this.write("warn", event, fields); }

  error(event, error, fields = {}) {
    this.write("error", event, { ...fields, errorClass: error instanceof Error ? error.name : "UnknownError" });
  }
}
