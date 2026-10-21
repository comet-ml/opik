import React from "react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";

import { LogExperiment } from "@/types/playground";
import useRunCompletionToast from "@/v2/pages/PlaygroundPage/useRunCompletionToast";

const toast = vi.fn();
const get = vi.fn();

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast }),
}));

vi.mock("@/api/api", () => ({
  default: { get: (...args: unknown[]) => get(...args) },
  EXPERIMENTS_REST_ENDPOINT: "/v1/private/experiments/",
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({ activeWorkspaceName: "test-workspace" }),
  ),
  useActiveProjectId: () => "project-id",
}));

const SERVER_NAMES: Record<string, string> = {
  "/v1/private/experiments/e1": "brave_tiger_1234",
  "/v1/private/experiments/e2": "calm_river_5678",
};

const experiment = (id: string, name?: string): LogExperiment => ({
  id,
  name,
  datasetName: "dataset",
});

let queryClient: QueryClient;

const announce = (experiments: LogExperiment[]) => {
  const { result } = renderHook(() => useRunCompletionToast("dataset-id"), {
    wrapper: ({ children }) => (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    ),
  });
  result.current(experiments);
};

describe("useRunCompletionToast names", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    });
    toast.mockReset();
    get.mockReset();
    get.mockImplementation(async (url: string) => ({
      data: { name: SERVER_NAMES[url] },
    }));
  });

  it("lists the names the server gave an auto-named run", async () => {
    announce([experiment("e2"), experiment("e1")]);

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(toast.mock.calls[0][0].description).toBe(
      "2 experiments created: brave_tiger_1234 • calm_river_5678",
    );
  });

  it("only asks the server for names it does not know", async () => {
    announce([experiment("e1"), experiment("e3", "foo_b")]);

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(get.mock.calls.map(([url]) => url)).toEqual([
      "/v1/private/experiments/e1",
    ]);
    expect(toast.mock.calls[0][0].description).toBe(
      "2 experiments created: brave_tiger_1234 • foo_b",
    );
  });

  it("still announces the run when a name cannot be read", async () => {
    get.mockRejectedValue(new Error("Network Error"));

    announce([experiment("e1")]);

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(toast.mock.calls[0][0].description).toBe("1 experiment created");
  });

  it("announces the run without the name when the lookup hangs", async () => {
    vi.useFakeTimers();
    get.mockImplementation(() => new Promise(() => {}));

    announce([experiment("e1")]);
    await vi.advanceTimersByTimeAsync(2999);
    expect(toast).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    expect(toast).toHaveBeenCalledTimes(1);
    expect(toast.mock.calls[0][0].description).toBe("1 experiment created");
  });

  it("reads a name the name box already loaded instead of asking again", async () => {
    queryClient.setQueryData(["experiment", { experimentId: "e1" }], {
      id: "e1",
      name: "brave_tiger_1234",
    });

    announce([experiment("e1")]);

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    expect(get).not.toHaveBeenCalled();
    expect(toast.mock.calls[0][0].description).toBe(
      "1 experiment created: brave_tiger_1234",
    );
  });

  it("shares one request with the name box when both ask at once", async () => {
    let resolveLookup: (value: unknown) => void = () => {};
    get.mockImplementation(
      () => new Promise((resolve) => (resolveLookup = resolve)),
    );
    const boxLookup = queryClient.fetchQuery({
      queryKey: ["experiment", { experimentId: "e1" }],
      queryFn: () =>
        get("/v1/private/experiments/e1").then(
          (response: { data: unknown }) => response.data,
        ),
    });

    announce([experiment("e1")]);
    resolveLookup({ data: { name: "brave_tiger_1234" } });

    await waitFor(() => expect(toast).toHaveBeenCalledTimes(1));
    await boxLookup;
    expect(get).toHaveBeenCalledTimes(1);
    expect(toast.mock.calls[0][0].description).toBe(
      "1 experiment created: brave_tiger_1234",
    );
  });

  it("announces a named run right away", () => {
    announce([experiment("e1", "foo_a"), experiment("e2", "foo_b")]);

    expect(get).not.toHaveBeenCalled();
    expect(toast.mock.calls[0][0].description).toBe(
      "2 experiments created: foo_a • foo_b",
    );
  });
});
