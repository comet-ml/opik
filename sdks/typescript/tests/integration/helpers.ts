const POLL_TIMEOUT_MS = 5_000;
const POLL_INTERVAL_MS = 200;

export async function pollUntil<T>(
  fetch: () => Promise<T | undefined>,
  timeoutMs = POLL_TIMEOUT_MS
): Promise<T> {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    const result = await fetch();
    if (result !== undefined) return result;
    await new Promise((r) => setTimeout(r, POLL_INTERVAL_MS));
  }
  throw new Error("pollUntil: timeout");
}
