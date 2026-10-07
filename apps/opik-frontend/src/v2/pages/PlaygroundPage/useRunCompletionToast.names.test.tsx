import { beforeEach, describe, expect, it, vi } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";

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

const announce = (experiments: LogExperiment[]) => {
  const { result } = renderHook(() => useRunCompletionToast("dataset-id"));
  result.current(experiments);
};

describe("useRunCompletionToast names", () => {
  beforeEach(() => {
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

  it("announces a named run right away", () => {
    announce([experiment("e1", "foo_a"), experiment("e2", "foo_b")]);

    expect(get).not.toHaveBeenCalled();
    expect(toast.mock.calls[0][0].description).toBe(
      "2 experiments created: foo_a • foo_b",
    );
  });
});
