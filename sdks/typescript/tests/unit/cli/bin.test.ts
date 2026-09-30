import { describe, it, expect, beforeAll } from "vitest";
import { spawnSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { build } from "tsup";

/**
 * The executable seam: what `npx opik …` does once it is a built bin.
 *
 * Exercised through a real child process against a stub `uv`, because the parts
 * that break this in the field - which arguments reach `uv tool run`, the exit
 * code coming back, the bundle keeping its shebang - are not reachable by
 * importing a module.
 */

const DOCS_URL = "https://www.comet.com/docs/opik/mcp-server";

let cliPath: string;

function scratchDir(): string {
  return fs.mkdtempSync(path.join(os.tmpdir(), "opik-cli-bin-"));
}

/** A `uv` that answers `--version`, echoes the handoff, and exits as told. */
function stubUvDir(exitCode = 0): string {
  const directory = scratchDir();
  const uv = path.join(directory, "uv");
  fs.writeFileSync(
    uv,
    `#!/bin/sh
if [ "$1" = "--version" ]; then echo "uv 0.0.0-stub"; exit 0; fi
echo "HANDOFF: $@"
echo "LAUNCHER: $OPIK_CLI_LAUNCHER"
exit ${exitCode}
`,
  );
  fs.chmodSync(uv, 0o755);
  return directory;
}

function runCli(args: string[], env: NodeJS.ProcessEnv) {
  return spawnSync(process.execPath, [cliPath, ...args], {
    encoding: "utf8",
    // No inherited PATH: uv must be found where the test puts it, or nowhere.
    env: { HOME: scratchDir(), OPIK_ANALYTICS_ENABLE: "false", ...env },
  });
}

describe.skipIf(process.platform === "win32")("the built opik bin", () => {
  beforeAll(async () => {
    // Built inside the package rather than in a temp directory: the bundle keeps
    // its dependencies external, exactly as the published one does, so it has to
    // sit somewhere `node_modules` resolves from.
    const cacheDir = path.resolve("node_modules", ".cache");
    fs.mkdirSync(cacheDir, { recursive: true });
    const outDir = fs.mkdtempSync(path.join(cacheDir, "opik-cli-bin-"));
    await build({
      config: false,
      entry: { cli: "src/opik/cli/bin.ts" },
      format: ["esm"],
      outDir,
      banner: { js: "#!/usr/bin/env node" },
      silent: true,
    });
    // What the published package declares, so Node reads the bundle as ESM here
    // for the same reason it does once installed.
    fs.writeFileSync(
      path.join(outDir, "package.json"),
      JSON.stringify({ type: "module" }),
    );
    cliPath = path.join(outDir, "cli.js");
  }, 120_000);

  it("is executable as a bin, shebang and all", () => {
    expect(
      fs.readFileSync(cliPath, "utf8").startsWith("#!/usr/bin/env node"),
    ).toBe(true);
  });

  it("hands every argument to `uv tool run opik`, untouched", () => {
    const result = runCli(["mcp", "configure", "--ai-client", "cursor"], {
      PATH: stubUvDir(),
    });

    expect(result.stdout).toContain(
      "HANDOFF: tool run opik mcp configure --ai-client cursor",
    );
    expect(result.status).toBe(0);
  });

  it("tells the Opik CLI how it was launched", () => {
    const result = runCli(["mcp", "configure"], { PATH: stubUvDir() });

    expect(result.stdout).toContain("LAUNCHER: npx");
  });

  it("exits with the code the Opik CLI exited with", () => {
    const result = runCli(["mcp", "configure"], { PATH: stubUvDir(3) });

    expect(result.status).toBe(3);
  });

  it("points at the docs and fails when uv is nowhere to be found", () => {
    const result = runCli(["mcp", "configure"], { PATH: scratchDir() });

    expect(result.stderr).toContain("uv, which is not installed");
    expect(result.stderr).toContain(DOCS_URL);
    expect(result.status).toBe(1);
  });

  it("finds a uv that is installed but not on PATH", () => {
    const home = scratchDir();
    const localBin = path.join(home, ".local", "bin");
    fs.mkdirSync(localBin, { recursive: true });
    fs.copyFileSync(path.join(stubUvDir(), "uv"), path.join(localBin, "uv"));
    fs.chmodSync(path.join(localBin, "uv"), 0o755);

    const result = runCli(["mcp", "status"], {
      PATH: scratchDir(),
      HOME: home,
    });

    expect(result.stdout).toContain("HANDOFF: tool run opik mcp status");
    expect(result.status).toBe(0);
  });
});
