import { readFile } from "node:fs/promises";
import { Authenticator } from "./auth.mjs";
import { MetadataLogger } from "./logger.mjs";
import { createRelayServer, listen, close } from "./server.mjs";
import { DurableStore } from "./store.mjs";

const logger = new MetadataLogger();

async function main() {
  const statePath = process.env.RELAY_STATE_PATH ?? "/var/lib/continuity-relay/state.json";
  const authPath = process.env.RELAY_AUTH_PATH ?? "/run/relay-auth/auth.json";
  const certPath = process.env.RELAY_TLS_CERT_PATH ?? "/run/relay-tls/server.pem";
  const keyPath = process.env.RELAY_TLS_KEY_PATH ?? "/run/relay-tls/server-key.pem";
  const host = process.env.RELAY_HOST ?? "0.0.0.0";
  const port = Number(process.env.RELAY_PORT ?? "8443");
  if (!Number.isInteger(port) || port < 0 || port > 65_535) throw new TypeError("invalid port");
  const [store, authenticator, cert, key] = await Promise.all([
    DurableStore.open({ statePath }), Authenticator.fromFile(authPath), readFile(certPath), readFile(keyPath),
  ]);
  const server = createRelayServer({ tls: { cert, key, minVersion: "TLSv1.2" }, store, authenticator, logger });
  const address = await listen(server, host, port);
  logger.info("server.ready", { port: address.port, status: 200 });
  const shutdown = async () => {
    await close(server);
    logger.info("server.stopped", { status: 200 });
  };
  process.once("SIGTERM", shutdown);
  process.once("SIGINT", shutdown);
}

main().catch((error) => {
  logger.error("server.start_failed", error, { status: 500 });
  process.exitCode = 1;
});
