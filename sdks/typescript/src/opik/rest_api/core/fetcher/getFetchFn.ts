import { RUNTIME } from "../runtime";

/**
 * Returns a fetch function based on the runtime
 */
export async function getFetchFn(): Promise<any> {
  if (typeof fetch == "function") {
    return fetch;
  }

  // In Node.js 18+ environments, use native fetch
  if (
    RUNTIME.type === "node" &&
    RUNTIME.parsedVersion != null &&
    RUNTIME.parsedVersion >= 18
  ) {
    return fetch;
  }

  return fetch;
}
