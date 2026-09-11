// Built dynamically so the ESC control char never appears in source (avoids
// eslint no-control-regex). Matches ANSI SGR sequences like ESC[31m / ESC[0m.
const ANSI_RE = new RegExp(`${String.fromCharCode(27)}\\[[0-9;]*m`, "g");
const ERROR_LINE_RE = /error|exception|traceback|failed|fatal/i;

const stripAnsi = (value: string): string => value.replace(ANSI_RE, "");

/**
 * Best-effort failure reason from studio log output. Studio runs have no
 * structured error field — the traceback lives in the log stream — so we
 * surface the deepest error-like line (ANSI stripped), or null when the logs
 * carry nothing that looks like an error.
 */
export const extractErrorFromLogs = (logContent: string): string | null => {
  const lines = logContent
    .split("\n")
    .map((line) => stripAnsi(line).trim())
    .filter(Boolean);

  for (let i = lines.length - 1; i >= 0; i--) {
    if (ERROR_LINE_RE.test(lines[i])) return lines[i];
  }
  return null;
};
