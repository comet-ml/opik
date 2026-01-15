/**
 * Wait and retry utilities
 */

export async function waitFor(
  condition: () => Promise<boolean>,
  options: {
    timeout?: number;
    initialDelay?: number;
    errorMessage?: string;
  } = {}
): Promise<void> {
  const {
    timeout = 10000,
    initialDelay = 1000,
    errorMessage = 'Condition not met within timeout',
  } = options;

  const startTime = Date.now();
  let delay = initialDelay;

  while (Date.now() - startTime < timeout) {
    try {
      if (await condition()) {
        return;
      }
    } catch (error) {
      // Continue retrying
    }

    await sleep(delay);
    delay = Math.min(delay * 2, timeout - (Date.now() - startTime));
  }

  throw new Error(`${errorMessage} (timeout: ${timeout}ms)`);
}

export function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export async function retry<T>(
  fn: () => Promise<T>,
  options: {
    maxAttempts?: number;
    initialDelay?: number;
    maxDelay?: number;
    onRetry?: (error: Error, attempt: number) => void;
  } = {}
): Promise<T> {
  const {
    maxAttempts = 3,
    initialDelay = 1000,
    maxDelay = 10000,
    onRetry,
  } = options;

  let lastError: Error;
  let delay = initialDelay;

  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      return await fn();
    } catch (error) {
      lastError = error as Error;

      if (attempt === maxAttempts) {
        break;
      }

      if (onRetry) {
        onRetry(lastError, attempt);
      }

      await sleep(delay);
      delay = Math.min(delay * 2, maxDelay);
    }
  }

  throw new Error(
    `Failed after ${maxAttempts} attempts. Last error: ${lastError!.message}`
  );
}
