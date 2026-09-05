import { randomBytes } from "node:crypto";
import { chmod, mkdir, open, readFile, rename, stat } from "node:fs/promises";
import { dirname } from "node:path";
import { StateError } from "./errors.mjs";

export async function atomicWrite(path, value) {
  const directory = dirname(path);
  const tempPath = `${path}.${process.pid}.${randomBytes(6).toString("hex")}.tmp`;
  await mkdir(directory, { recursive: true, mode: 0o700 });
  let handle;
  try {
    handle = await open(tempPath, "wx", 0o600);
    await handle.writeFile(`${JSON.stringify(value)}\n`, "utf8");
    await handle.sync();
    await handle.close();
    handle = undefined;
    await rename(tempPath, path);
    await chmod(path, 0o600);
    const directoryHandle = await open(directory, "r");
    await directoryHandle.sync();
    await directoryHandle.close();
  } catch (error) {
    if (handle !== undefined) await handle.close().catch(() => undefined);
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
