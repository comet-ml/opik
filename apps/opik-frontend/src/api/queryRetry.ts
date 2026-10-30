import { DefaultOptions } from "@tanstack/react-query";
import { isAxiosError } from "axios";

export const MAX_QUERY_RETRIES = 2;

/**
 * Shared retry policy for the app's React Query client. It intentionally
 * diverges from React Query's defaults, which retry every failure three
 * times regardless of type:
 *
 * - Retried: network errors and timeouts (a request with no response),
 *   5xx server errors, and 429 rate limiting.
 * - Never retried: other 4xx responses, which are terminal and only get
 *   delayed by retrying, and non-HTTP errors thrown by query functions.
 * - `MAX_QUERY_RETRIES` caps a query at two retries (three requests total).
 *
 * Broadening the predicate adds in-process requests and delays surfacing
 * permanent errors, so anything added to it needs a real reason to be
 * transient.
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
