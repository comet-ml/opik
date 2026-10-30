import { describe, it, expect, vi } from "vitest";
import { AxiosError, AxiosResponse } from "axios";
import {
  isTransientQueryError,
  shouldRetryQuery,
  MAX_QUERY_RETRIES,
} from "@/api/queryRetry";
import { queryClient as appQueryClient } from "@/api/queryClient";

const axiosErrorWithStatus = (status: number) =>
  new AxiosError(
    "Request failed",
    AxiosError.ERR_BAD_REQUEST,
    undefined,
    undefined,
    {
      status,
    } as AxiosResponse,
  );

describe("isTransientQueryError", () => {
  it.each([500, 502, 503, 504])("retries server error %i", (status) => {
    expect(isTransientQueryError(axiosErrorWithStatus(status))).toBe(true);
  });

  it("retries rate limiting (429)", () => {
    expect(isTransientQueryError(axiosErrorWithStatus(429))).toBe(true);
  });

  it("retries network errors with no response", () => {
    expect(
      isTransientQueryError(
        new AxiosError("Network Error", AxiosError.ERR_NETWORK),
      ),
    ).toBe(true);
  });

  it("retries request timeouts", () => {
    expect(
      isTransientQueryError(
        new AxiosError("timeout exceeded", AxiosError.ECONNABORTED),
      ),
    ).toBe(true);
  });

  it.each([400, 401, 403, 404, 409, 422])(
    "does not retry client error %i",
    (status) => {
      expect(isTransientQueryError(axiosErrorWithStatus(status))).toBe(false);
    },
  );

  it("does not retry non-HTTP errors", () => {
    expect(isTransientQueryError(new Error("something threw"))).toBe(false);
    expect(isTransientQueryError("boom")).toBe(false);
    expect(isTransientQueryError(undefined)).toBe(false);
  });
});

describe("shouldRetryQuery", () => {
  it("retries transient errors below the retry cap", () => {
    expect(
      shouldRetryQuery(0, new AxiosError("x", AxiosError.ERR_NETWORK)),
    ).toBe(true);
    expect(
      shouldRetryQuery(MAX_QUERY_RETRIES - 1, axiosErrorWithStatus(503)),
    ).toBe(true);
  });

  it("stops retrying once the cap is reached", () => {
    expect(
      shouldRetryQuery(
        MAX_QUERY_RETRIES,
        new AxiosError("x", AxiosError.ERR_NETWORK),
      ),
    ).toBe(false);
  });

  it("never retries client errors", () => {
    expect(shouldRetryQuery(0, axiosErrorWithStatus(404))).toBe(false);
  });
});

describe("app query client", () => {
  it("retries a network failure before giving up", async () => {
    vi.useFakeTimers();
    try {
      let calls = 0;
      const promise = appQueryClient.fetchQuery({
        queryKey: ["query-retry", "network"],
        queryFn: () => {
          calls += 1;
          throw new AxiosError("Network Error", AxiosError.ERR_NETWORK);
        },
      });
      const rejection = expect(promise).rejects.toBeInstanceOf(AxiosError);

      await vi.advanceTimersByTimeAsync(10_000);

      await rejection;
      expect(calls).toBe(MAX_QUERY_RETRIES + 1);
    } finally {
      vi.useRealTimers();
    }
  });

  it("fails a client error immediately without retrying", async () => {
    vi.useFakeTimers();
    try {
      let calls = 0;
      const promise = appQueryClient.fetchQuery({
        queryKey: ["query-retry", "not-found"],
        queryFn: () => {
          calls += 1;
          throw axiosErrorWithStatus(404);
        },
      });
      const rejection = expect(promise).rejects.toBeInstanceOf(AxiosError);

      await vi.advanceTimersByTimeAsync(10_000);

      await rejection;
      expect(calls).toBe(1);
    } finally {
      vi.useRealTimers();
    }
  });
});
