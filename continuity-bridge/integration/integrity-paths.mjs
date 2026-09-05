import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir } from "node:fs/promises";
import { isAbsolute, join, relative, resolve, sep } from "node:path";

export const TREE_DIGEST_DOMAIN = "continuity-bridge/tree-digest\0v1\0";
const TREE_RECORD_FILE = 1;
const TREE_RECORD_END = 0;
const MAX_UINT64 = (1n << 64n) - 1n;

export class IntegrityPathError extends Error {
  constructor(reason, path) {
    super(`integrity traversal rejected ${reason}: ${path}`);
    this.name = "IntegrityPathError";
    this.code = "TASK7_INTEGRITY_PATH";
  }
}

function pathLabel(root, path) {
  const label = relative(root, path);
  if (label === "") return ".";
  if (label === ".." || label.startsWith(`..${sep}`) || isAbsolute(label)) {
    throw new IntegrityPathError("path escape", label);
  }
  return label.split(sep).join("/");
}

function compareUtf8(left, right) {
  return Buffer.compare(Buffer.from(left.name, "utf8"), Buffer.from(right.name, "utf8"));
}

function compareTreeRecords(left, right) {
  return Buffer.compare(Buffer.from(left.label, "utf8"), Buffer.from(right.label, "utf8"));
}

export function frameUnsignedLength(value, field) {
  let length;
  if (typeof value === "bigint") length = value;
  else if (Number.isSafeInteger(value)) length = BigInt(value);
  else throw new IntegrityPathError("length overflow", field);
  if (length < 0n || length > MAX_UINT64) throw new IntegrityPathError("length overflow", field);
  const frame = Buffer.alloc(8);
  frame.writeBigUInt64BE(length);
  return frame;
}

export function frameTreeRecord(label, content) {
  const segments = typeof label === "string" ? label.split("/") : [];
  if (segments.length === 0 || segments.some((segment) => segment === "" || segment === "." || segment === "..") ||
      label.includes("\0") || isAbsolute(label)) {
    throw new IntegrityPathError("invalid framed path", typeof label === "string" && label !== "" ? label : "<empty>");
  }
  const pathBytes = Buffer.from(label, "utf8");
  if (pathBytes.toString("utf8") !== label) throw new IntegrityPathError("invalid framed path", label);
  if (!Buffer.isBuffer(content)) throw new TypeError("tree digest content must be a Buffer");
  return Buffer.concat([Buffer.from([TREE_RECORD_FILE]), frameUnsignedLength(pathBytes.length, "path"), pathBytes,
    frameUnsignedLength(content.length, "content"), content]);
}

function sameIdentity(left, right) {
  return left.dev === right.dev && left.ino === right.ino && left.mode === right.mode &&
    left.size === right.size && left.mtimeNs === right.mtimeNs && left.ctimeNs === right.ctimeNs;
}

async function checkedStatus(root, path, expected) {
  let status;
  try { status = await lstat(path, { bigint: true }); }
  catch (cause) { throw new IntegrityPathError("path replacement", pathLabel(root, path), { cause }); }
  const label = pathLabel(root, path);
  if (status.isSymbolicLink()) throw new IntegrityPathError("symbolic link", label);
  if (expected !== undefined && !sameIdentity(status, expected)) throw new IntegrityPathError("path replacement", label);
  return status;
}

async function readStableFile(root, path, expected) {
  const label = pathLabel(root, path);
  let handle;
  try { handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW); }
  catch (cause) {
    if (cause.code === "ELOOP") throw new IntegrityPathError("symbolic link", label);
    throw new IntegrityPathError("path replacement", label);
  }
  try {
    const opened = await handle.stat({ bigint: true });
    if (!opened.isFile() || !sameIdentity(opened, expected)) throw new IntegrityPathError("path replacement", label);
    const bytes = await handle.readFile();
    const afterHandle = await handle.stat({ bigint: true });
    const afterPath = await checkedStatus(root, path, opened);
    if (!sameIdentity(opened, afterHandle) || !sameIdentity(opened, afterPath)) {
      throw new IntegrityPathError("path replacement", label);
    }
    return bytes;
  } finally { await handle.close(); }
}

export async function digestTree(root, options = {}) {
  const normalizedRoot = resolve(root);
  const excluded = new Set(options.excludedDirectoryNames ?? []);
  const digest = createHash("sha256");
  const records = [];
  digest.update(Buffer.from(TREE_DIGEST_DOMAIN, "utf8"));
  const visit = async (directory, expected) => {
    const before = await checkedStatus(normalizedRoot, directory, expected);
    if (!before.isDirectory()) throw new IntegrityPathError("non-directory", pathLabel(normalizedRoot, directory));
    const entries = (await readdir(directory, { withFileTypes: true })).sort(compareUtf8);
    await checkedStatus(normalizedRoot, directory, before);
    for (const entry of entries) {
      const path = join(directory, entry.name);
      const status = await checkedStatus(normalizedRoot, path);
      const label = pathLabel(normalizedRoot, path);
      if (status.isDirectory()) {
        if (!excluded.has(entry.name)) await visit(path, status);
      } else if (status.isFile()) {
        records.push({ label, content: await readStableFile(normalizedRoot, path, status) });
      } else {
        throw new IntegrityPathError("unsupported entry", label);
      }
    }
    await checkedStatus(normalizedRoot, directory, before);
  };
  await visit(normalizedRoot);
  records.sort(compareTreeRecords);
  for (let index = 0; index < records.length; index += 1) {
    if (index > 0 && records[index - 1].label === records[index].label) {
      throw new IntegrityPathError("duplicate path", records[index].label);
    }
    digest.update(frameTreeRecord(records[index].label, records[index].content));
  }
  digest.update(Buffer.from([TREE_RECORD_END]));
  return digest.digest("hex");
}

export async function digestFile(path) {
  const normalized = resolve(path);
  const root = resolve(normalized, "..");
  const status = await checkedStatus(root, normalized);
  if (!status.isFile()) throw new IntegrityPathError("non-file", pathLabel(root, normalized));
  const digest = createHash("sha256");
  digest.update(Buffer.from(TREE_DIGEST_DOMAIN, "utf8"));
  digest.update(frameTreeRecord(pathLabel(root, normalized), await readStableFile(root, normalized, status)));
  digest.update(Buffer.from([TREE_RECORD_END]));
  return digest.digest("hex");
}

export function digestTreeProgram(root) {
  return `import { createHash } from "node:crypto";
import { constants } from "node:fs";
import { lstat, open, readdir } from "node:fs/promises";
import { isAbsolute, join, relative, resolve, sep } from "node:path";
const TREE_DIGEST_DOMAIN = ${JSON.stringify(TREE_DIGEST_DOMAIN)};
const TREE_RECORD_FILE = ${TREE_RECORD_FILE};
const TREE_RECORD_END = ${TREE_RECORD_END};
const MAX_UINT64 = (1n << 64n) - 1n;
${IntegrityPathError.toString()}
${pathLabel.toString()}
${compareUtf8.toString()}
${compareTreeRecords.toString()}
${frameUnsignedLength.toString()}
${frameTreeRecord.toString()}
${sameIdentity.toString()}
${checkedStatus.toString()}
${readStableFile.toString()}
${digestTree.toString()}
process.stdout.write(await digestTree(${JSON.stringify(root)}));`;
}
