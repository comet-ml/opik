import { createHash } from "node:crypto";
import os from "node:os";

/** Lets an installation count its distinct SDK clients. */
export const CLIENT_ID_HEADER = "X-Opik-Client-Id";

/**
 * An anonymous identifier for this machine and OS user: a hash of the hostname
 * and username, never the raw values. Derived the way the Python SDK's
 * `get_client_identifier` is, so one machine counts once across both SDKs.
 */
export function clientIdentifier(): string {
  return createHash("sha256")
    .update(os.hostname() + userName())
    .digest("hex");
}

// Mirrors Python's `getpass.getuser()` and Opik's "unknown" fallback, so a host
// with no passwd entry (e.g. a container running as an arbitrary UID) hashes the same in both SDKs.
export function userName(env: NodeJS.ProcessEnv = process.env): string {
  for (const name of ["LOGNAME", "USER", "LNAME", "USERNAME"]) {
    if (env[name]) return env[name];
  }
  try {
    return os.userInfo().username;
  } catch {
    return "unknown";
  }
}
