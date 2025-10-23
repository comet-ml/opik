import { RUNTIME } from "@/rest_api/core/runtime";

/**
 * Safely access process.env with fallback for non-Node.js environments
 */
export function getProcessEnv(): Record<string, string | undefined> {
  if (typeof process !== "undefined" && process.env) {
    return process.env;
  }
  return {};
}

/**
 * Check if the runtime supports filesystem operations
 * Returns true for Node.js, Bun, and Deno environments
 */
export function isNodeLikeRuntime(): boolean {
  return (
    RUNTIME.type === "node" || RUNTIME.type === "bun" || RUNTIME.type === "deno"
  );
}
