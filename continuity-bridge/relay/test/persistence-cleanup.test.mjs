import assert from "node:assert/strict";
import crypto from "node:crypto";
import { mkdir, readFile, readdir, rm, symlink, writeFile } from "node:fs/promises";
import { syncBuiltinESMExports } from "node:module";
import { basename, join } from "node:path";
import test from "node:test";
import { atomicWrite } from "../src/persistence.mjs";
import { DurableStore } from "../src/store.mjs";
import { ANDROID, MACOS, clipboard, fixedClock, tempState } from "./helpers.mjs";

test("Given a temporary filename collision When exclusive creation fails Then the existing file is preserved", async (context) => {
  // Given
  const fixture = await tempState();
  context.after(() => rm(fixture.directory, { recursive: true, force: true }));
  const suffix = "abcdef012345";
  const collision = `${fixture.path}.${process.pid}.${suffix}.tmp`;
  await writeFile(collision, "PREEXISTING_TEMP_SENTINEL");
  const random = context.mock.method(crypto, "randomBytes", () => Buffer.from(suffix, "hex"));
  syncBuiltinESMExports();
  try {
    // When
    await assert.rejects(atomicWrite(fixture.path, { value: "new" }), { name: "StateError" });
    // Then
    assert.equal(await readFile(collision, "utf8"), "PREEXISTING_TEMP_SENTINEL");
  } finally {
    random.mock.restore();
    syncBuiltinESMExports();
  }
});

test("Given a real rename failure When an atomic state write fails Then its payload-bearing temporary file is removed", async (context) => {
  // Given
  const fixture = await tempState();
  context.after(() => rm(fixture.directory, { recursive: true, force: true }));
  await mkdir(fixture.path);
  // When
  await assert.rejects(atomicWrite(fixture.path, { payload: "FAILED_WRITE_PRIVATE_SENTINEL" }), { name: "StateError" });
  // Then
  assert.deepEqual(await readdir(fixture.directory), [basename(fixture.path)]);
});

test("Given crash leftovers beside committed state When the relay opens Then only its exact-format regular temporary files are removed", async (context) => {
  // Given
  const fixture = await tempState();
  context.after(() => rm(fixture.directory, { recursive: true, force: true }));
  const initial = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  await initial.publish(ANDROID, clipboard());
  const committed = await readFile(fixture.path, "utf8");
  const stale = `${basename(fixture.path)}.123.abcdef012345.tmp`;
  const unrelated = ["state.json.stale.tmp", "state.json.123.not-hex.tmp", "other-state.json.123.abcdef012345.tmp"];
  for (const name of [stale, ...unrelated]) await writeFile(join(fixture.directory, name), "TEMP_PAYLOAD_SENTINEL");
  const matchingDirectory = `${basename(fixture.path)}.124.abcdef012345.tmp`;
  await mkdir(join(fixture.directory, matchingDirectory));
  const matchingSymlink = `${basename(fixture.path)}.125.abcdef012345.tmp`;
  await symlink(join(fixture.directory, unrelated[0]), join(fixture.directory, matchingSymlink));
  // When
  const reopened = await DurableStore.open({ statePath: fixture.path, clock: fixedClock() });
  const fetched = await reopened.fetch(MACOS, "0");
  // Then
  assert.equal(await readFile(fixture.path, "utf8"), committed);
  assert.deepEqual(fetched.events.map((entry) => entry.event.eventId), ["event-clipboard-1"]);
  assert.deepEqual((await readdir(fixture.directory)).sort(),
    [basename(fixture.path), ...unrelated, matchingDirectory, matchingSymlink].sort());
});
