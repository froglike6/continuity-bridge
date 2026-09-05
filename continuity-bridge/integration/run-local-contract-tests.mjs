import { runFull } from "./full-orchestrator.mjs";

function output(value) { process.stdout.write(`${JSON.stringify(value)}\n`); }

const args = process.argv.slice(2);
if (args.length !== 1 || args[0] !== "full") {
  process.stderr.write("usage: node continuity-bridge/integration/run-local-contract-tests.mjs full\n");
  process.exitCode = 2;
} else {
  const result = await runFull();
  for (const marker of result.markers) output(marker);
  output(result.global);
}
