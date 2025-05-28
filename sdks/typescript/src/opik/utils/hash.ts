import xxhash, { XXHashAPI } from "xxhash-wasm";

// Singleton hash API instance
let hashApi: XXHashAPI | null = null;

/**
 * Ensures the hash function is initialized.
 */
export async function initHashApi(): Promise<XXHashAPI> {
  if (!hashApi) {
    hashApi = await xxhash();
  }
  return hashApi;
}
