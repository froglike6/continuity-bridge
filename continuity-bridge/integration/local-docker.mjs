import { cp, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { isAbsolute, join } from "node:path";
import { loadConfig } from "./http-client.mjs";
import { digestTree, digestTreeProgram, IntegrityPathError } from "./integrity-paths.mjs";
import { run } from "./process-runner.mjs";

const EXPECTED_RELAY_IMAGE = "sha256:a0c6f3f6bb56bcb0b478b31cbf0d7e949e7cd2d2efa821c6c238c18845233ece";

export class DockerHarness {
  constructor(workspace, environment = process.env) {
    this.workspace = workspace;
    this.project = environment.TASK7_COMPOSE_PROJECT ?? `cbtask7full${process.pid}`;
    this.image = environment.TASK7_RELAY_IMAGE;
    this.expectedImage = environment.TASK7_EXPECTED_RELAY_IMAGE_ID ?? EXPECTED_RELAY_IMAGE;
    if (!/^[a-z0-9][a-z0-9_-]{2,63}$/.test(this.project)) {
      throw new Error("TASK7_COMPOSE_PROJECT must be a Docker Compose-safe project name");
    }
    this.runtimeRoot = environment.TASK7_RUNTIME_ROOT;
    if (this.runtimeRoot !== undefined &&
        (!isAbsolute(this.runtimeRoot) || !this.runtimeRoot.includes("/continuity-bridge-task7-"))) {
      throw new Error("TASK7_RUNTIME_ROOT must be an absolute task-owned continuity-bridge-task7 path");
    }
    if (this.image === undefined) throw new Error("TASK7_RELAY_IMAGE is required");
    if (!/^sha256:[0-9a-f]{64}$/.test(this.image)) {
      throw new Error("TASK7_RELAY_IMAGE must be an immutable sha256 image ID");
    }
    if (!/^sha256:[0-9a-f]{64}$/.test(this.expectedImage)) {
      throw new Error("TASK7_EXPECTED_RELAY_IMAGE_ID must be an immutable sha256 image ID");
    }
    this.logs = [];
  }

  async preflight(inspect = (image) => run("docker", ["image", "inspect", image], { acceptCodes: [0, 1] })) {
    let result;
    try { result = await inspect(this.image); }
    catch (cause) { throw new Error(`TASK7_RELAY_IMAGE does not exist: ${this.image}`, { cause }); }
    if (result?.code !== 0) throw new Error(`TASK7_RELAY_IMAGE does not exist: ${this.image}`);
    let images;
    try { images = JSON.parse(result.stdout); } catch { images = undefined; }
    if (!Array.isArray(images) || images.length !== 1 || typeof images[0]?.Id !== "string") {
      throw new Error(`TASK7_RELAY_IMAGE inspect response is invalid: ${this.image}`);
    }
    if (images[0].Id !== this.expectedImage) throw new Error(`TASK7_RELAY_IMAGE is not approved: ${this.image}`);
    this.imageId = images[0].Id;
    return images[0];
  }

  async initialize() {
    await this.preflight();
    this.directory = this.runtimeRoot ?? await mkdtemp(join(tmpdir(), "continuity-bridge-task7-full."));
    await mkdir(this.directory, { recursive: true });
    this.runtime = join(this.directory, "runtime");
    this.statePath = join(this.runtime, "state", "state.json");
    this.authPath = join(this.runtime, "auth", "auth.json");
    this.caPath = join(this.runtime, "tls", "ca.pem");
    await mkdir(join(this.runtime, "state"), { recursive: true });
    const relay = join(this.workspace, "continuity-bridge", "relay");
    await run(join(relay, "scripts", "generate-local-tls.sh"), [join(this.runtime, "tls")]);
    await run(join(relay, "scripts", "generate-local-auth.sh"), [join(this.runtime, "auth")]);
    const auth = JSON.parse(await readFile(this.authPath, "utf8"));
    for (const credential of auth.credentials) {
      if (credential.revoked !== true) credential.deviceId = credential.role === "android"
        ? "device-android-fixture" : "device-macos-fixture";
    }
    await writeFile(this.authPath, `${JSON.stringify(auth)}\n`, { mode: 0o600 });
    this.composePath = join(this.directory, "compose.yaml");
    await writeFile(this.composePath, this.compose(), { mode: 0o600 });
    await this.composeRun(["up", "-d"]);
    await this.waitHealthy();
    await this.verifyImageSource(join(relay, "src"));
    return this.config();
  }

  compose() {
    return `services:\n  relay:\n    image: ${this.image}\n    user: "1000:1000"\n    environment:\n      RELAY_HOST: 0.0.0.0\n      RELAY_PORT: "8443"\n      RELAY_STATE_PATH: /var/lib/continuity-relay/state.json\n      RELAY_AUTH_PATH: /run/relay-auth/auth.json\n      RELAY_TLS_CERT_PATH: /run/relay-tls/server.pem\n      RELAY_TLS_KEY_PATH: /run/relay-tls/server-key.pem\n    ports: ["127.0.0.1:8443:8443"]\n    volumes:\n      - ${join(this.runtime, "state")}:/var/lib/continuity-relay:rw\n      - ${this.caPath}:/run/relay-tls/ca.pem:ro\n      - ${join(this.runtime, "tls", "server.pem")}:/run/relay-tls/server.pem:ro\n      - ${join(this.runtime, "tls", "server-key.pem")}:/run/relay-tls/server-key.pem:ro\n      - ${this.authPath}:/run/relay-auth/auth.json:ro\n    tmpfs:\n      - "/tmp:rw,noexec,nosuid,nodev,size=16m,uid=1000,gid=1000"\n    read_only: true\n    cap_drop: [ALL]\n    security_opt: [no-new-privileges:true]\n    restart: unless-stopped\n    pids_limit: 64\n    mem_limit: 128m\n    healthcheck:\n      test: ["CMD", "node", "-e", "require('https').get({hostname:'localhost',port:8443,path:'/v1/health',ca:require('fs').readFileSync('/run/relay-tls/ca.pem')},r=>process.exit(r.statusCode===200?0:1)).on('error',()=>process.exit(1))"]\n      interval: 5s\n      timeout: 3s\n      retries: 12\n      start_period: 2s\n`;
  }

  async config() { return loadConfig({ caPath: this.caPath, authPath: this.authPath }); }
  container() { return `${this.project}-relay-1`; }
  network() { return `${this.project}_default`; }
  async composeRun(args, timeoutMs) { return run("docker", ["compose", "-p", this.project, "-f", this.composePath, ...args], { timeoutMs }); }

  async waitHealthy() {
    for (let attempt = 0; attempt < 20; attempt += 1) {
      const result = await run("docker", ["inspect", "-f", "{{.State.Running}}", this.container()], { acceptCodes: [0, 1] });
      if (result.code === 0 && result.stdout.trim() === "true") {
        const config = await this.config();
        try {
          const { request } = await import("./http-client.mjs");
          const response = await request(config, { timeoutMs: 1_000 });
          if (response.status === 200 && response.body?.status === "ok") return;
        } catch (error) {
          if (attempt === 19) throw new Error("relay health request failed", { cause: error });
        }
      }
      await new Promise((resolve) => setTimeout(resolve, 250));
    }
    throw new Error("relay readiness deadline exceeded");
  }

  async captureLogs() {
    const result = await run("docker", ["logs", this.container()], { acceptCodes: [0, 1] });
    this.logs.push(result.stdout, result.stderr);
  }

  async imageMetadata() {
    const built = JSON.parse((await run("docker", ["image", "inspect", this.image])).stdout)[0];
    const base = JSON.parse((await run("docker", ["image", "inspect", "node:24-bookworm-slim"])).stdout)[0];
    return Object.freeze({ imageId: built.Id, imageDigests: built.RepoDigests ?? [], sourceDigest: this.sourceDigest,
      imageSourceDigest: this.imageSourceDigest, baseId: base.Id, baseDigests: base.RepoDigests ?? [] });
  }

  async verifyImageSource(source) {
    this.sourceDigest = await digestTree(source);
    const script = digestTreeProgram("/app/src");
    this.imageSourceDigest = (await run("docker", ["exec", this.container(), "node", "--input-type=module", "-e", script])).stdout;
    if (this.imageSourceDigest !== this.sourceDigest) throw new Error("relay image source digest mismatch");
  }

  async resetState(label) {
    await this.captureLogs();
    await this.composeRun(["down"]);
    try { await rename(this.statePath, join(this.runtime, "state", `state.${label}.json`)); } catch (error) { if (error.code !== "ENOENT") throw error; }
    await this.composeRun(["up", "-d"]);
    await this.waitHealthy();
    return this.config();
  }

  async cleanup() {
    if (this.composePath !== undefined) await this.composeRun(["down"], 30_000).catch(() => undefined);
    if (this.directory !== undefined) await rm(this.directory, { recursive: true, force: true });
  }
}

export async function copyProduct(source, destination) {
  const excludedDirectoryNames = [".build", "build", "dist"];
  const sourceDigest = await digestTree(source, { excludedDirectoryNames });
  await cp(source, destination, { recursive: true, filter: (path) => !/(^|\/)\.build(?:\/|$)/.test(path) &&
    !/(^|\/)(?:build|dist)(?:\/|$)/.test(path) });
  const destinationDigest = await digestTree(destination, { excludedDirectoryNames });
  if (destinationDigest !== sourceDigest) throw new IntegrityPathError("copy digest mismatch", ".");
}
