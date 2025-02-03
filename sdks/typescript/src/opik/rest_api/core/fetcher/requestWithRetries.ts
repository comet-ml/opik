const INITIAL_RETRY_DELAY = 1000; // in milliseconds
const MAX_RETRY_DELAY = 60000; // in milliseconds
const DEFAULT_MAX_RETRIES = 2;
const JITTER_FACTOR = 0.2; // 20% random jitter

function addJitter(delay: number): number {
    // Generate a random value between -JITTER_FACTOR and +JITTER_FACTOR
    const jitterMultiplier = 1 + (Math.random() * 2 - 1) * JITTER_FACTOR;
    return delay * jitterMultiplier;
}

export async function requestWithRetries(
    requestFn: () => Promise<Response>,
    maxRetries: number = DEFAULT_MAX_RETRIES,
): Promise<Response> {
    let response: Response = await requestFn();

    for (let i = 0; i < maxRetries; ++i) {
        if ([408, 429].includes(response.status) || response.status >= 500) {
            // Calculate base delay using exponential backoff (in milliseconds)
            const baseDelay = Math.min(INITIAL_RETRY_DELAY * Math.pow(2, i), MAX_RETRY_DELAY);

            // Add jitter to the delay
            const delayWithJitter = addJitter(baseDelay);

            await new Promise((resolve) => setTimeout(resolve, delayWithJitter));
            response = await requestFn();
        } else {
            break;
        }
    }
    return response!;
}
