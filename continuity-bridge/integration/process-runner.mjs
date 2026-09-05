import { spawn } from "node:child_process";

export function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd: options.cwd, env: options.env ?? process.env,
      stdio: ["ignore", "pipe", "pipe"] });
    const stdout = [];
    const stderr = [];
    const timer = setTimeout(() => child.kill("SIGKILL"), options.timeoutMs ?? 60_000);
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.once("error", (error) => { clearTimeout(timer); reject(new Error(`process launch failed: ${command}`, { cause: error })); });
    child.once("exit", (code, signal) => {
      clearTimeout(timer);
      const result = Object.freeze({ code, signal, stdout: Buffer.concat(stdout).toString("utf8"),
        stderr: Buffer.concat(stderr).toString("utf8") });
      if ((options.acceptCodes ?? [0]).includes(code)) resolve(result);
      else {
        const error = new Error(`process failed: ${command} exit=${code} signal=${signal ?? "none"}`);
        Object.defineProperty(error, "result", { value: result });
        reject(error);
      }
    });
  });
}
