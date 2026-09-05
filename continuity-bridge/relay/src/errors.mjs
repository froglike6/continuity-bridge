const DEFINITIONS = Object.freeze({
  malformed_json: [400, "Malformed JSON."],
  invalid_event: [400, "Invalid event."],
  invalid_ack: [400, "Invalid acknowledgement."],
  invalid_query: [400, "Invalid query."],
  unsupported_protocol_version: [400, "Unsupported protocol version."],
  unsupported_kind: [400, "Unsupported event kind."],
  unauthorized: [401, "Unauthorized."],
  identity_mismatch: [403, "Authenticated identity does not match the body."],
  direction_forbidden: [403, "Event direction is forbidden."],
  event_id_conflict: [409, "Event ID conflicts with an accepted event."],
  sequence_conflict: [409, "Origin sequence conflicts with an accepted event."],
  stale_sequence: [409, "Origin sequence or epoch is stale."],
  payload_too_large: [413, "Payload is too large."],
  state_error: [500, "Persistent relay state is unavailable."],
});

export class RelayError extends Error {
  constructor(code) {
    const definition = DEFINITIONS[code];
    super(definition[1]);
    this.name = "RelayError";
    this.status = definition[0];
    this.code = code;
  }
}

export class StateError extends Error {
  constructor(cause) {
    super("Persistent relay state is unavailable.", { cause });
    this.name = "StateError";
  }
}

export function failure(code) {
  const error = new RelayError(code);
  return Object.freeze({ status: error.status, code: error.code });
}

export function errorBody(code) {
  const error = new RelayError(code);
  return { error: { code: error.code, message: error.message } };
}
