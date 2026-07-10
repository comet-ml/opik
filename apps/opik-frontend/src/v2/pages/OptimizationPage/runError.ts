// Built dynamically so the ESC control char never appears in source (avoids
// eslint no-control-regex). Matches ANSI SGR sequences like ESC[31m / ESC[0m.
const ANSI_RE = new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, "g");

// Curated, high-level, user-facing notices the backend appends to the log
// stream when a run fails — the optimizer worker (which has the real exception
// type) and the stalled-run reaper both write these (OPIK-7159). The frontend
// does NOT classify errors itself; it only surfaces what the backend produced.
const SYSTEM_PREFIX = "[System]";

const stripAnsi = (value: string): string => value.replace(ANSI_RE, "");

export const GENERIC_RUN_ERROR =
  "The optimization run ran into an unexpected error and stopped. Open the logs below for the full details.";

/** The most recent curated `[System]` notice (ANSI-stripped, prefix removed), or null. */
export const extractSystemNotice = (logContent: string): string | null => {
  const lines = logContent
    .split("\n")
    .map((line) => stripAnsi(line).trim())
    .filter(Boolean);

  for (let i = lines.length - 1; i >= 0; i--) {
    if (lines[i].startsWith(SYSTEM_PREFIX)) {
      return lines[i].slice(SYSTEM_PREFIX.length).trim() || null;
    }
  }
  return null;
};

/**
 * High-level, user-facing failure message for the run error panel.
 *
 * The message is authored on the backend (the worker classifies the real
 * exception; the reaper writes infra reasons) and shipped as a curated
 * `[System]` line in the log stream. Here we simply surface it, falling back to
 * a generic message when none is present (e.g. runs that failed before this was
 * added). The raw traceback/exception detail stays in "View logs".
 */
export const getRunErrorMessage = (logContent: string): string =>
  extractSystemNotice(logContent) ?? GENERIC_RUN_ERROR;
