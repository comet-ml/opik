import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useFeedbackScoresBatchMutation from "./useFeedbackScoresBatchMutation";
import api, { TRACES_REST_ENDPOINT, SPANS_REST_ENDPOINT } from "@/api/api";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { AxiosResponse } from "axios";

const mockToast = vi.fn();
vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

vi.mock("@/api/api", () => ({
  default: {
    put: vi.fn(),
  },
  TRACES_REST_ENDPOINT: "/v1/private/traces/",
  SPANS_REST_ENDPOINT: "/v1/private/spans/",
  TRACES_KEY: "traces",
  SPANS_KEY: "spans",
  TRACE_KEY: "trace",
  COMPARE_EXPERIMENTS_KEY: "compare-experiments",
}));

const mockApiPut = vi.mocked(api.put);

const renderTestHook = () => {
  const queryClient = new QueryClient({
    defaultOptions: {
      mutations: { retry: false },
      queries: { retry: false },
    },
  });

  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );

  const hookResult = renderHook(() => useFeedbackScoresBatchMutation(), {
    wrapper,
  });
  return { hookResult, queryClient };
};

describe("useFeedbackScoresBatchMutation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("submits a single batch PUT request for traces", async () => {
    mockApiPut.mockResolvedValueOnce({ data: {} } as AxiosResponse);
    const { hookResult } = renderTestHook();

    await act(async () => {
      await hookResult.result.current.mutateAsync({
        scores: [
          { id: "trace-1", name: "helpfulness", value: 5 },
          { id: "trace-2", name: "helpfulness", value: 5 },
        ],
        isSpanType: false,
      });
    });

    expect(api.put).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith(
      `${TRACES_REST_ENDPOINT}feedback-scores`,
      {
        scores: [
          {
            id: "trace-1",
            name: "helpfulness",
            category_name: undefined,
            value: 5,
            reason: undefined,
            project_name: undefined,
            source: FEEDBACK_SCORE_TYPE.ui,
          },
          {
            id: "trace-2",
            name: "helpfulness",
            category_name: undefined,
            value: 5,
            reason: undefined,
            project_name: undefined,
            source: FEEDBACK_SCORE_TYPE.ui,
          },
        ],
      },
    );
  });

  it("submits batch PUT request to spans endpoint when isSpanType is true", async () => {
    mockApiPut.mockResolvedValueOnce({ data: {} } as AxiosResponse);
    const { hookResult } = renderTestHook();

    await act(async () => {
      await hookResult.result.current.mutateAsync({
        scores: [
          { id: "span-1", name: "accuracy", value: 1, categoryName: "good" },
        ],
        isSpanType: true,
      });
    });

    expect(api.put).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith(
      `${SPANS_REST_ENDPOINT}feedback-scores`,
      {
        scores: [
          {
            id: "span-1",
            name: "accuracy",
            category_name: "good",
            value: 1,
            reason: undefined,
            project_name: undefined,
            source: FEEDBACK_SCORE_TYPE.ui,
          },
        ],
      },
    );
  });

  it("emits destructive toast on error", async () => {
    mockApiPut.mockRejectedValueOnce({
      message: "Server error",
      response: { data: { message: "Batch failed" } },
    });
    const { hookResult } = renderTestHook();

    await act(async () => {
      try {
        await hookResult.result.current.mutateAsync({
          scores: [{ id: "t1", name: "test", value: 1 }],
        });
      } catch {
        // Expected
      }
    });

    expect(mockToast).toHaveBeenCalledWith({
      title: "Error",
      description: "Batch failed",
      variant: "destructive",
    });
  });

  // -------------------------------------------------------------------------
  // Edge-case: sequential 500-item chunking (>500 scores)
  // -------------------------------------------------------------------------
  it("splits >500 scores into sequential 500-item chunks", async () => {
    // 502 items → chunk 1: 500, chunk 2: 2 → exactly 2 PUT calls
    mockApiPut.mockResolvedValue({ data: {} } as AxiosResponse);
    const { hookResult } = renderTestHook();

    const scores = Array.from({ length: 502 }, (_, i) => ({
      id: `trace-${i}`,
      name: "score",
      value: i,
      projectName: "my-project",
    }));

    await act(async () => {
      await hookResult.result.current.mutateAsync({
        scores,
        isSpanType: false,
      });
    });

    expect(api.put).toHaveBeenCalledTimes(2);

    // First chunk must have exactly 500 items in original order
    const firstCallPayload = mockApiPut.mock.calls[0][1] as {
      scores: Array<{ id: string; project_name?: string }>;
    };
    const firstCallScores = firstCallPayload.scores;
    expect(firstCallScores).toHaveLength(500);
    expect(firstCallScores[0].id).toBe("trace-0");
    expect(firstCallScores[499].id).toBe("trace-499");

    // Second chunk must have the remaining 2 items
    const secondCallPayload = mockApiPut.mock.calls[1][1] as {
      scores: Array<{ id: string; project_name?: string }>;
    };
    const secondCallScores = secondCallPayload.scores;
    expect(secondCallScores).toHaveLength(2);
    expect(secondCallScores[0].id).toBe("trace-500");
    expect(secondCallScores[1].id).toBe("trace-501");

    // project_name must be forwarded for every item
    expect(firstCallScores[0].project_name).toBe("my-project");
    expect(secondCallScores[0].project_name).toBe("my-project");
  });

  // -------------------------------------------------------------------------
  // Edge-case: 429 rate-limit retry terminates after 3 attempts
  // -------------------------------------------------------------------------
  it("makes three attempts on 429 then throws, emitting destructive toast", async () => {
    vi.useFakeTimers();

    const rate429 = {
      message: "Too Many Requests",
      response: { status: 429, data: { message: "Rate limited" } },
      status: 429,
    };

    // All 3 attempts return 429 → must stop and throw
    mockApiPut
      .mockRejectedValueOnce(rate429)
      .mockRejectedValueOnce(rate429)
      .mockRejectedValueOnce(rate429);

    const { hookResult } = renderTestHook();

    await act(async () => {
      const mutationPromise = hookResult.result.current
        .mutateAsync({ scores: [{ id: "t1", name: "n", value: 1 }] })
        .catch(() => {
          /* expected rejection */
        });

      // Flush all pending setTimeout-based back-off delays
      await vi.runAllTimersAsync();
      await mutationPromise;
    });

    vi.useRealTimers();

    // maxAttempts === 3, so exactly 3 PUT calls must have been made
    expect(api.put).toHaveBeenCalledTimes(3);
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ variant: "destructive" }),
    );
  });

  // -------------------------------------------------------------------------
  // Edge-case: chunk 2 failure halts pipeline + destructive toast
  // -------------------------------------------------------------------------
  it("stops processing after a mid-batch chunk failure", async () => {
    // chunk 1 (500 items) succeeds, chunk 2 (2 items) fails
    mockApiPut
      .mockResolvedValueOnce({ data: {} } as AxiosResponse)
      .mockRejectedValueOnce({
        message: "Server error",
        response: { data: { message: "Chunk 2 failed" } },
      });

    const { hookResult } = renderTestHook();

    const scores = Array.from({ length: 502 }, (_, i) => ({
      id: `t-${i}`,
      name: "score",
      value: i,
    }));

    await act(async () => {
      try {
        await hookResult.result.current.mutateAsync({
          scores,
          isSpanType: false,
        });
      } catch {
        // Expected – chunk 2 throws, propagating the error
      }
    });

    // Must stop after 2 calls: chunk 1 ok, chunk 2 error → no further chunks
    expect(api.put).toHaveBeenCalledTimes(2);

    // onError must fire the destructive toast
    expect(mockToast).toHaveBeenCalledWith(
      expect.objectContaining({ variant: "destructive" }),
    );
  });
});
