import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { digestTree, digestTreeProgram, frameTreeRecord, frameUnsignedLength } from "./integrity-paths.mjs";

async function withFlatTree(entries, run) {
  const root = await mkdtemp(join(tmpdir(), "task7-framing-test."));
  try {
    for (const [path, content] of entries) {
      const segments = path.split("/");
      if (segments.length > 1) await mkdir(join(root, ...segments.slice(0, -1)), { recursive: true });
      await writeFile(join(root, ...segments), content);
    }
    await run(root);
  } finally { await rm(root, { recursive: true, force: true }); }
}

function oldFlatDigest(entries) {
  const digest = createHash("sha256");
  for (const [path, content] of [...entries].sort(([left], [right]) => left.localeCompare(right))) {
    digest.update(path);
    digest.update(content);
  }
  return digest.digest("hex");
}

function parseRecord(frame) {
  let offset = 0;
  const type = frame[offset];
  offset += 1;
  const pathLength = Number(frame.readBigUInt64BE(offset));
  offset += 8;
  const path = frame.subarray(offset, offset + pathLength);
  offset += pathLength;
  const contentLength = Number(frame.readBigUInt64BE(offset));
  offset += 8;
  const content = frame.subarray(offset, offset + contentLength);
  offset += contentLength;
  assert.equal(offset, frame.length);
  return { type, pathLength, path, contentLength, content };
}

test("Given a to bc and ab to c When digested Then old streams collide and framed trees differ", async () => {
  const left = [["a", "bc"]];
  const right = [["ab", "c"]];
  assert.equal(oldFlatDigest(left), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  assert.equal(oldFlatDigest(left), oldFlatDigest(right));
  await withFlatTree(left, async (leftRoot) => {
    await withFlatTree(right, async (rightRoot) => assert.notEqual(await digestTree(leftRoot), await digestTree(rightRoot)));
  });
});

test("Given empty and nonempty files When digested Then record boundaries remain distinct", async () => {
  await withFlatTree([["a", ""]], async (emptyRoot) => {
    await withFlatTree([["a", "x"]], async (nonemptyRoot) =>
      assert.notEqual(await digestTree(emptyRoot), await digestTree(nonemptyRoot)));
  });
});

test("Given prefix paths and contents When digested Then framing prevents concatenation ambiguity", async () => {
  await withFlatTree([["a", "b"]], async (leftRoot) => {
    await withFlatTree([["ab", ""]], async (rightRoot) =>
      assert.notEqual(await digestTree(leftRoot), await digestTree(rightRoot)));
  });
});

test("Given the same multiple files created in reverse order When digested Then sorting is stable", async () => {
  const forward = [["a", "1"], ["nested/나", "🙂"], ["z", ""]];
  await withFlatTree(forward, async (leftRoot) => {
    const expected = "467311d66ee335711df47615838f7a831c1b36c1360e7be8a4d3f927c5d21f38";
    assert.equal(await digestTree(leftRoot), expected);
    const embedded = spawnSync(process.execPath, ["--input-type=module", "-e", digestTreeProgram(leftRoot)],
      { encoding: "utf8", timeout: 5_000 });
    assert.equal(embedded.status, 0, embedded.stderr);
    assert.equal(embedded.stdout, expected);
    await withFlatTree([...forward].reverse(), async (rightRoot) => assert.equal(await digestTree(rightRoot), expected));
  });
});

test("Given Unicode path and content When host and embedded digest run Then byte semantics match", async () => {
  await withFlatTree([["한글/🙂.txt", "값🙂"]], async (root) => {
    const embedded = spawnSync(process.execPath, ["--input-type=module", "-e", digestTreeProgram(root)],
      { encoding: "utf8", timeout: 5_000 });
    assert.equal(embedded.status, 0, embedded.stderr);
    assert.equal(embedded.stdout, await digestTree(root));
  });
});

test("Given an empty tree When digested twice Then the versioned terminator is deterministic", async () => {
  await withFlatTree([], async (root) => assert.equal(await digestTree(root), await digestTree(root)));
});

test("Given a framed record When parsed Then type and UTF-8 byte lengths are exact", () => {
  const parsed = parseRecord(frameTreeRecord("한글/🙂.txt", Buffer.from("값🙂", "utf8")));
  assert.equal(parsed.type, 1);
  assert.equal(parsed.pathLength, Buffer.byteLength("한글/🙂.txt", "utf8"));
  assert.equal(parsed.path.toString("utf8"), "한글/🙂.txt");
  assert.equal(parsed.contentLength, Buffer.byteLength("값🙂", "utf8"));
  assert.equal(parsed.content.toString("utf8"), "값🙂");
});

test("Given length boundaries When framed Then uint64 is big-endian and overflow fails closed", () => {
  assert.equal(frameUnsignedLength(0x0102030405060708n, "test").toString("hex"), "0102030405060708");
  assert.equal(frameUnsignedLength(0n, "test").toString("hex"), "0000000000000000");
  for (const value of [-1, 1.5, Number.MAX_SAFE_INTEGER + 1, 1n << 64n]) {
    assert.throws(() => frameUnsignedLength(value, "test"),
      { name: "IntegrityPathError", message: "integrity traversal rejected length overflow: test" });
  }
});

test("Given impossible or ambiguous record paths When framed Then they are rejected", () => {
  for (const path of ["", ".", "..", "../a", "a/../b", "/absolute", "a//b", "nul\0path", "\ud800"]) {
    assert.throws(() => frameTreeRecord(path, Buffer.alloc(0)), { name: "IntegrityPathError" });
  }
});

test("Given empty and nonempty record content When framed Then parsed content lengths differ", () => {
  assert.equal(parseRecord(frameTreeRecord("a", Buffer.alloc(0))).contentLength, 0);
  assert.equal(parseRecord(frameTreeRecord("a", Buffer.from("x"))).contentLength, 1);
});
