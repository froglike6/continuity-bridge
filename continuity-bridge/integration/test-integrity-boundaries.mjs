import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { treeDigest } from "./build-matrix.mjs";
import { digestTreeProgram } from "./integrity-paths.mjs";
import { copyProduct, DockerHarness } from "./local-docker.mjs";

const APPROVED = "sha256:a0c6f3f6bb56bcb0b478b31cbf0d7e949e7cd2d2efa821c6c238c18845233ece";

async function withTree(run) {
  const temporary = await mkdtemp(join(tmpdir(), "task7-integrity-test."));
  const root = join(temporary, "root");
  await mkdir(root);
  await writeFile(join(root, "plain.txt"), "plain\n");
  try { await run({ temporary, root }); } finally { await rm(temporary, { recursive: true, force: true }); }
}

async function rejectsUnsafeLink(setup, expectedPath) {
  await withTree(async (fixture) => {
    await setup(fixture);
    await assert.rejects(() => treeDigest(fixture.root), (error) => {
      assert.equal(error.name, "IntegrityPathError");
      assert.equal(error.code, "TASK7_INTEGRITY_PATH");
      assert.equal(error.message, `integrity traversal rejected symbolic link: ${expectedPath}`);
      return true;
    });
  });
}

test("Given a clean tree When digested twice Then the digest is deterministic", async () => {
  await withTree(async ({ root }) => assert.equal(await treeDigest(root), await treeDigest(root)));
});

test("Given a symlink to a file When a tree is digested Then traversal fails closed", async () => {
  await rejectsUnsafeLink(({ root }) => symlink("plain.txt", join(root, "linked-file")), "linked-file");
});

test("Given a symlink to a directory When a tree is digested Then traversal fails closed", async () => {
  await rejectsUnsafeLink(async ({ root }) => {
    await mkdir(join(root, "directory"));
    await symlink("directory", join(root, "linked-directory"));
  }, "linked-directory");
});

test("Given a dangling symlink When a tree is digested Then traversal fails closed", async () => {
  await rejectsUnsafeLink(({ root }) => symlink("missing", join(root, "dangling")), "dangling");
});

test("Given an outside-tree symlink When a tree is digested Then traversal fails closed", async () => {
  await rejectsUnsafeLink(async ({ temporary, root }) => {
    await writeFile(join(temporary, "outside.txt"), "outside\n");
    await symlink(join(temporary, "outside.txt"), join(root, "outside-link"));
  }, "outside-link");
});

test("Given the digest root itself is a symlink When traversed Then traversal fails closed", async () => {
  await withTree(async ({ temporary, root }) => {
    const linkedRoot = join(temporary, "linked-root");
    await symlink(root, linkedRoot);
    await assert.rejects(() => treeDigest(linkedRoot), (error) => {
      assert.equal(error.name, "IntegrityPathError");
      assert.equal(error.code, "TASK7_INTEGRITY_PATH");
      assert.equal(error.message, "integrity traversal rejected symbolic link: .");
      return true;
    });
  });
});

test("Given a source symlink When copyProduct starts Then it fails before copying", async () => {
  await withTree(async ({ temporary, root }) => {
    await symlink("plain.txt", join(root, "linked-file"));
    await assert.rejects(() => copyProduct(root, join(temporary, "copy")),
      { name: "IntegrityPathError", message: "integrity traversal rejected symbolic link: linked-file" });
  });
});

test("Given a clean image-source tree When the embedded digest program runs Then it matches the host digest", async () => {
  await withTree(async ({ root }) => {
    const result = spawnSync(process.execPath, ["--input-type=module", "-e", digestTreeProgram(root)],
      { encoding: "utf8", timeout: 5_000 });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout, await treeDigest(root));
  });
});

test("Given an image-source symlink When the embedded digest program runs Then it fails closed", async () => {
  await withTree(async ({ root }) => {
    await symlink("plain.txt", join(root, "linked-file"));
    const result = spawnSync(process.execPath, ["--input-type=module", "-e", digestTreeProgram(root)],
      { encoding: "utf8", timeout: 5_000 });
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /IntegrityPathError: integrity traversal rejected symbolic link: linked-file/);
  });
});

test("Given invalid image inputs When the harness is constructed Then each form fails closed", () => {
  const malformed = ["continuity-bridge-relay:task2", `sha256:${"A".repeat(64)}`, `sha256:${"a".repeat(63)}`];
  assert.throws(() => new DockerHarness("/workspace", {}), { message: "TASK7_RELAY_IMAGE is required" });
  for (const image of malformed) {
    assert.throws(() => new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: image }),
      { message: "TASK7_RELAY_IMAGE must be an immutable sha256 image ID" });
  }
});

test("Given a declared Compose project When the harness is constructed Then task-owned names are exact", () => {
  const image = `sha256:${"a".repeat(64)}`;
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: image, TASK7_COMPOSE_PROJECT: "cbtask7current123" });
  assert.equal(harness.project, "cbtask7current123");
  assert.equal(harness.container(), "cbtask7current123-relay-1");
  assert.equal(harness.network(), "cbtask7current123_default");
});

test("Given a declared runtime root When the harness is constructed Then runtime paths are exact", () => {
  const image = `sha256:${"a".repeat(64)}`;
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: image,
    TASK7_RUNTIME_ROOT: "/tmp/continuity-bridge-task7-current-proof" });
  assert.equal(harness.runtimeRoot, "/tmp/continuity-bridge-task7-current-proof");
});

test("Given a nonexistent immutable image When preflight runs Then it reports nonexistence", async () => {
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: `sha256:${"f".repeat(64)}` });
  await assert.rejects(() => harness.preflight(async () => ({ code: 1, stdout: "", stderr: "missing" })),
    { message: `TASK7_RELAY_IMAGE does not exist: sha256:${"f".repeat(64)}` });
});

test("Given a wrong existing immutable image When preflight runs Then it is not approved", async () => {
  const wrong = `sha256:${"b".repeat(64)}`;
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: wrong });
  const inspect = async () => ({ code: 0, stdout: JSON.stringify([{ Id: wrong }]), stderr: "" });
  await assert.rejects(() => harness.preflight(inspect), { message: `TASK7_RELAY_IMAGE is not approved: ${wrong}` });
});

test("Given a current-source immutable image pin When preflight runs Then that exact pin is approved", async () => {
  const current = `sha256:${"c".repeat(64)}`;
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: current, TASK7_EXPECTED_RELAY_IMAGE_ID: current });
  const inspect = async () => ({ code: 0, stdout: JSON.stringify([{ Id: current }]), stderr: "" });
  assert.equal((await harness.preflight(inspect)).Id, current);
});

test("Given the exact approved existing image When preflight runs Then it passes without runtime startup", async () => {
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: APPROVED });
  const inspect = async () => ({ code: 0, stdout: JSON.stringify([{ Id: APPROVED }]), stderr: "" });
  assert.equal((await harness.preflight(inspect)).Id, APPROVED);
  assert.equal(harness.directory, undefined);
});

test("Given misleading inspect output or hidden nonzero status When preflight runs Then it fails closed", async () => {
  const harness = new DockerHarness("/workspace", { TASK7_RELAY_IMAGE: APPROVED });
  await assert.rejects(() => harness.preflight(async () => ({ code: 0, stdout: "[]", stderr: "" })),
    { message: `TASK7_RELAY_IMAGE inspect response is invalid: ${APPROVED}` });
  await assert.rejects(() => harness.preflight(async () => ({ code: 1,
    stdout: JSON.stringify([{ Id: APPROVED }]), stderr: "hidden failure" })),
    { message: `TASK7_RELAY_IMAGE does not exist: ${APPROVED}` });
});

test("Given the environment object changes after construction When preflight runs Then the captured pin remains exact", async () => {
  const environment = { TASK7_RELAY_IMAGE: APPROVED };
  const harness = new DockerHarness("/workspace", environment);
  environment.TASK7_RELAY_IMAGE = `sha256:${"c".repeat(64)}`;
  const inspect = async (image) => {
    assert.equal(image, APPROVED);
    return { code: 0, stdout: JSON.stringify([{ Id: APPROVED }]), stderr: "" };
  };
  assert.equal((await harness.preflight(inspect)).Id, APPROVED);
});
