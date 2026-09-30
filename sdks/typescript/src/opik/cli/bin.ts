import { spawnSync } from "node:child_process";

import { createReporter } from "./analytics";
import { findUv } from "./uv";

/**
 * `npx opik …` hands its arguments to `uvx opik …`.
 *
 * The Opik CLI is the Python one; this exists so that reaching it costs an `npx`
 * rather than knowing about uv first. Everything past the command name is passed
 * through untouched, so `npx opik mcp configure --ai-client cursor` is the same
 * run as `uvx opik mcp configure --ai-client cursor`.
 */

const UV_DOCS_URL =
  "https://www.comet.com/docs/opik/mcp-server#install-uv-if-you-dont-have-it";

async function main(): Promise<number> {
  const args = process.argv.slice(2);
  const reporter = createReporter(args);

  reporter.track("invoked");

  const uv = findUv();
  reporter.track("uv_detected", {
    uv_found: uv !== undefined,
    uv_source: uv?.source,
  });

  if (uv === undefined) {
    process.stderr.write(
      "The Opik MCP runs through uv, which is not installed on this machine.\n\n" +
        `How to install uv: ${UV_DOCS_URL}\n\n` +
        "Once it is installed, re-run this command.\n",
    );
    // Separate from the detection above, which says what this machine has:
    // this says the user was sent to the docs instead of getting a setup.
    reporter.track("error_shown", { reason: "uv_missing" });
    await reporter.flush();
    return 1;
  }

  reporter.track("handoff");
  // Reported before the handoff rather than after it: `uvx` takes over the
  // terminal from here, and a Ctrl-C during setup would otherwise leave the run
  // looking like it never got this far.
  await reporter.flush();

  // `uv tool run` is what `uvx` is shorthand for. We hold a path to `uv`, not to
  // `uvx`, because that is what detection can tell us about.
  const result = spawnSync(uv.path, ["tool", "run", "opik", ...args], {
    stdio: "inherit",
  });

  if (result.error !== undefined) {
    process.stderr.write(`Could not run uv: ${result.error.message}\n`);
    return 1;
  }

  // A null status means a signal killed it - Ctrl-C, most likely. There is no
  // exit code to pass on, so report failure rather than a silent success.
  return result.status ?? 1;
}

main().then(
  (code) => {
    process.exitCode = code;
  },
  (error: unknown) => {
    process.stderr.write(`${String(error)}\n`);
    process.exitCode = 1;
  },
);
