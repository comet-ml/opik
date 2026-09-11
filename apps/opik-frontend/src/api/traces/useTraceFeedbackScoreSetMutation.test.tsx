import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook, act } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import useTraceFeedbackScoreSetMutation from "./useTraceFeedbackScoreSetMutation";
import api, { TRACES_REST_ENDPOINT, SPANS_REST_ENDPOINT } from "@/api/api";
import { AxiosError, AxiosResponse } from "axios";

const mockToast = vi.fn();
vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: mockToast }),
}));

vi.mock("@/store/AppStore", () => ({
  useLoggedInUserName: () => "tester",
}));

vi.mock("@/api/api", () => ({
  default: {
    put: vi.fn(),
  },
  TRACES_REST_ENDPOINT: "/v1/private/traces/",
  SPANS_REST_ENDPOINT: "/v1/private/spans/",
  TRACES_KEY: "traces",
  TRACE_KEY: "trace",
  SPANS_KEY: "spans",
  COMPARE_EXPERIMENTS_KEY: "compare-experiments",
}));

const mockApiPut = vi.mocked(api.put);

vi.mock("@/lib/feedback-scores", () => ({
  generateUpdateMutation: vi.fn(),
  setExperimentsCompareCache: vi.fn().mockResolvedValue(undefined),
  setSpansCache: vi.fn().mockResolvedValue(undefined),
  setTraceCache: vi.fn().mockResolvedValue(undefined),
  setTracesCache: vi.fn().mockResolvedValue(undefined),
}));

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

  return renderHook(() => useTraceFeedbackScoreSetMutation(), { wrapper });
};

describe("useTraceFeedbackScoreSetMutation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("calls API put on trace endpoint for trace feedback scores", async () => {
    mockApiPut.mockResolvedValueOnce({
      data: { id: "score-1" },
    } as AxiosResponse);
    const { result } = renderTestHook();

    await act(async () => {
      await result.current.mutateAsync({
        traceId: "trace-123",
        name: "accuracy",
        value: 1,
      });
    });

    expect(api.put).toHaveBeenCalledWith(
      `${TRACES_REST_ENDPOINT}trace-123/feedback-scores`,
      expect.objectContaining({
        name: "accuracy",
        value: 1,
      }),
    );
  });

  it("calls API put on span endpoint when spanId is provided", async () => {
    mockApiPut.mockResolvedValueOnce({
      data: { id: "score-2" },
    } as AxiosResponse);
    const { result } = renderTestHook();

    await act(async () => {
      await result.current.mutateAsync({
        traceId: "trace-123",
        spanId: "span-456",
        name: "accuracy",
        value: 1,
      });
    });

    expect(api.put).toHaveBeenCalledWith(
      `${SPANS_REST_ENDPOINT}span-456/feedback-scores`,
      expect.objectContaining({
        name: "accuracy",
        value: 1,
      }),
    );
  });

  it("emits destructive toast on error when silent is not true", async () => {
    const axiosError = new AxiosError("Network Error");
    axiosError.response = {
      status: 500,
      data: { message: "Internal server error" },
    } as AxiosResponse;
    mockApiPut.mockRejectedValueOnce(axiosError);

    const { result } = renderTestHook();

    await act(async () => {
      try {
        await result.current.mutateAsync({
          traceId: "trace-123",
          name: "accuracy",
          value: 1,
        });
      } catch {
        // Expected
      }
    });

    expect(mockToast).toHaveBeenCalledWith({
      title: "Error",
      description: "Internal server error",
      variant: "destructive",
    });
  });

  it("does NOT emit destructive toast on error when silent is true", async () => {
    const axiosError = new AxiosError("Network Error");
    axiosError.response = {
      status: 500,
      data: { message: "Internal server error" },
    } as AxiosResponse;
    mockApiPut.mockRejectedValueOnce(axiosError);
    const { result } = renderTestHook();

    await act(async () => {
      try {
        await result.current.mutateAsync({
          traceId: "trace-123",
          name: "accuracy",
          value: 1,
          silent: true,
        });
      } catch {
        // Expected mutation rejection
      }
    });

    expect(mockToast).not.toHaveBeenCalled();
  });
});
