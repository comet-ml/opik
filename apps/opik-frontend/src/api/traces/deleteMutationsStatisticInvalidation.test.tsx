import React from "react";
import { describe, it, expect, vi } from "vitest";
import { act, renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

const post = vi.fn(() => Promise.resolve({ data: {} }));
const del = vi.fn(() => Promise.resolve({ data: {} }));

vi.mock("@/api/api", () => ({
  default: {
    post: () => post(),
    delete: () => del(),
  },
  TRACES_REST_ENDPOINT: "/v1/private/traces/",
  TRACES_KEY: "traces",
  SPANS_KEY: "spans",
  THREADS_KEY: "threads",
  COMPARE_EXPERIMENTS_KEY: "compare-experiments",
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
}));

import useTracesBatchDeleteMutation from "./useTraceBatchDeleteMutation";
import useTraceDeleteMutation from "./useTraceDeleteMutation";
import useThreadBatchDeleteMutation from "./useThreadBatchDeleteMutation";

const PROJECT_ID = "project-1";

const renderMutation = <T,>(hook: () => T) => {
  const queryClient = new QueryClient({
    defaultOptions: { mutations: { retry: false }, queries: { retry: false } },
  });
  const invalidated: unknown[][] = [];
  queryClient.invalidateQueries = vi.fn(({ queryKey }) => {
    invalidated.push(queryKey as unknown[]);
    return Promise.resolve();
  }) as never;

  const wrapper = ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );

  return { ...renderHook(hook, { wrapper }), invalidated };
};

const invalidatedPrefixes = (invalidated: unknown[][]) =>
  invalidated.map((key) => key[0]);

describe("trace/thread delete mutations invalidate the metrics-card queries", () => {
  it("useTracesBatchDeleteMutation invalidates trace/span statistics and the KPI cards", async () => {
    const { result, invalidated } = renderMutation(() =>
      useTracesBatchDeleteMutation(),
    );

    await act(async () => {
      result.current.mutate({ ids: ["t1", "t2"], projectId: PROJECT_ID });
    });

    await waitFor(() => expect(invalidated.length).toBeGreaterThan(0));

    expect(invalidatedPrefixes(invalidated)).toContain("traces-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("spans-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("project-kpi-cards");
  });

  it("useTraceDeleteMutation invalidates trace/span statistics and the KPI cards", async () => {
    const { result, invalidated } = renderMutation(() =>
      useTraceDeleteMutation(),
    );

    await act(async () => {
      result.current.mutate({ traceId: "t1", projectId: PROJECT_ID });
    });

    await waitFor(() => expect(invalidated.length).toBeGreaterThan(0));

    expect(invalidatedPrefixes(invalidated)).toContain("traces-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("spans-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("project-kpi-cards");
  });

  it("useThreadBatchDeleteMutation invalidates thread/trace/span statistics and the KPI cards", async () => {
    const { result, invalidated } = renderMutation(() =>
      useThreadBatchDeleteMutation(),
    );

    await act(async () => {
      result.current.mutate({ ids: ["th1"], projectId: PROJECT_ID });
    });

    await waitFor(() => expect(invalidated.length).toBeGreaterThan(0));

    expect(invalidatedPrefixes(invalidated)).toContain("threads-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("traces-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("spans-statistic");
    expect(invalidatedPrefixes(invalidated)).toContain("project-kpi-cards");
  });
});
