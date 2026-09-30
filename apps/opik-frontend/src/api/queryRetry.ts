import { DefaultOptions } from "@tanstack/react-query";
import { isAxiosError } from "axios";

export const MAX_QUERY_RETRIES = 2;

/**
 * Only transient failures are worth a retry: network drops, timeouts,
 * server errors, and rate limiting. Client errors (4xx) are terminal —
 * retrying them just delays the error the user needs to see.
 */
export function isTransientQueryError(error: unknown): boolean {
  if (!isAxiosError(error)) {
    return false;
  }

  if (error.response) {
    return error.response.status >= 500 || error.response.status === 429;
  }

  // No response at all: the request never completed (network drop or timeout).
  return true;
}

export function shouldRetryQuery(
  failureCount: number,
  error: unknown,
): boolean {
  return failureCount < MAX_QUERY_RETRIES && isTransientQueryError(error);
}

export const QUERY_CLIENT_DEFAULT_OPTIONS: DefaultOptions = {
  queries: {
    retry: shouldRetryQuery,
  },
};
