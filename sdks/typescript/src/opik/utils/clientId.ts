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

function userName(): string {
  try {
    return os.userInfo().username;
  } catch {
    return "";
  }
}
