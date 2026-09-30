import { spawnSync } from "node:child_process";
import { accessSync, constants } from "node:fs";
import os from "node:os";
import path from "node:path";

export interface UvLocation {
  path: string;
  /** Where it turned up, which is what says whether the user's PATH is set up. */
  source: "path" | "install-dir";
}

const PROBE_TIMEOUT_MS = 10_000;

export function uvBinaryName(
  platform: NodeJS.Platform = process.platform,
): string {
  return platform === "win32" ? "uv.exe" : "uv";
}

/**
 * Where uv's installer puts the binary, in the order it prefers.
 *
 * Checked as well as PATH because a uv installed from a still-open shell is not
 * on this process's PATH: the installer edits a shell profile, which only takes
 * effect in shells started afterwards.
 */
export function uvCandidatePaths(
  env: NodeJS.ProcessEnv = process.env,
  platform: NodeJS.Platform = process.platform,
  home: string = os.homedir(),
): string[] {
  const directories = [
    env.XDG_BIN_HOME,
    env.CARGO_HOME === undefined ? undefined : path.join(env.CARGO_HOME, "bin"),
    path.join(home, ".local", "bin"),
    path.join(home, ".cargo", "bin"),
  ].filter((directory): directory is string => Boolean(directory));

  const binary = uvBinaryName(platform);
  return [...new Set(directories)].map((directory) =>
    path.join(directory, binary),
  );
}

export function findUv(): UvLocation | undefined {
  const onPath = uvBinaryName();
  if (runsSuccessfully(onPath)) {
    return { path: onPath, source: "path" };
  }

  for (const candidate of uvCandidatePaths()) {
    if (isExecutable(candidate) && runsSuccessfully(candidate)) {
      return { path: candidate, source: "install-dir" };
    }
  }

  return undefined;
}

function isExecutable(candidate: string): boolean {
  try {
    accessSync(candidate, constants.X_OK);
    return true;
  } catch {
    return false;
  }
}

function runsSuccessfully(command: string): boolean {
  const result = spawnSync(command, ["--version"], {
    stdio: "ignore",
    timeout: PROBE_TIMEOUT_MS,
  });
  return result.error === undefined && result.status === 0;
}
