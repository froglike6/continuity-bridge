import https from "node:https";
import { createHandler } from "./api.mjs";

export function createRelayServer({ tls, store, authenticator, logger }) {
  return https.createServer(tls, createHandler({ store, authenticator, logger }));
}

export function listen(server, host, port) {
  return new Promise((resolveListen, rejectListen) => {
    server.once("error", rejectListen);
    server.listen(port, host, () => {
      server.removeListener("error", rejectListen);
      resolveListen(server.address());
    });
  });
}

export function close(server) {
  return new Promise((resolveClose, rejectClose) => server.close((error) => error ? rejectClose(error) : resolveClose()));
}
