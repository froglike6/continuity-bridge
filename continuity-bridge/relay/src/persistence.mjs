import { randomBytes } from "node:crypto";
import { chmod, mkdir, open, readFile, readdir, rename, rm, stat } from "node:fs/promises";
import { basename, dirname, join } from "node:path";
import { StateError } from "./errors.mjs";

export async function atomicWrite(path, value) {
  const directory = dirname(path);
  const tempPath = `${path}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  let handle;
  let tempCreated = false;
  try {
    await mkdir(directory, { recursive: true, mode: 0o700 });
    handle = await open(tempPath, "wx", 0o600);
    tempCreated = true;
    await handle.writeFile(`${JSON.stringify(value)}\n`, "utf8");
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(tempPath, path);
    await chmod(path, 0o600);
    const directoryHandle = await open(directory, "r");
    try { await directoryHandle.sync(); }
    finally { await directoryHandle.close(); }
  } catch (error) {
    if (handle !== undefined) await handle.close().catch(() => undefined);
    if (tempCreated) await rm(tempPath, { force: true }).catch(() => undefined);
    throw new StateError(error);
  }
}

export async function cleanupTempFiles(path) {
  const directory = dirname(path);
  const prefix = `${basename(path)}.`;
  try {
    for (const entry of await readdir(directory, { withFileTypes: true })) {
      if (entry.isFile() && entry.name.startsWith(prefix) &&
          /^[1-9][0-9]*\.[0-9a-f]{12}\.tmp$/.test(entry.name.slice(prefix.length))) {
        await rm(join(directory, entry.name), { force: true });
      }
    }
  } catch (error) {
    if (error.code === "ENOENT") return;
    throw new StateError(error);
  }
}

export async function loadState(path) {
  try {
    await stat(path);
  } catch (error) {
    if (error.code === "ENOENT") return undefined;
    throw new StateError(error);
  }
  try {
    return JSON.parse(await readFile(path, "utf8"));
  } catch (error) {
    throw new StateError(error);
  }
}
