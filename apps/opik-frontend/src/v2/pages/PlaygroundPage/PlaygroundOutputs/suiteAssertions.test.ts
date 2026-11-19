import { describe, it, expect, vi, beforeEach } from "vitest";
import { renderHook } from "@testing-library/react";
import { QueryClient } from "@tanstack/react-query";

import { Experiment } from "@/types/datasets";
import {
  fetchSuiteAssertionCount,
  useSuiteAssertionCount,
} from "./suiteAssertions";

let versionData: unknown;

vi.mock("@/api/datasets/useDatasetVersionByHash", () => ({
  default: () => ({ data: versionData }),
  datasetVersionByHashKey: (params: unknown) => ["dataset-versions", params],
  getDatasetVersionByHash: vi.fn(),
}));

const judge = (assertions: string[]) => ({
  name: "llm_judge",
  type: "llm_judge",
  config: { schema: assertions.map((name) => ({ name, type: "BOOLEAN" })) },
});

const experimentWithVersion = (versionHash?: string) =>
  ({
    id: "exp-1",
    dataset_version_summary: versionHash
      ? { id: "v-1", version_hash: versionHash }
      : undefined,
  }) as unknown as Experiment;

beforeEach(() => {
  versionData = undefined;
});

describe("useSuiteAssertionCount", () => {
  it("should be unknown until the experiment loads", () => {
    const { result } = renderHook(() =>
      useSuiteAssertionCount("suite-1", undefined),
    );
    expect(result.current).toBeUndefined();
  });

  it("should be 0 for an experiment run without a suite version", () => {
    const { result } = renderHook(() =>
      useSuiteAssertionCount("suite-1", experimentWithVersion()),
    );
    expect(result.current).toBe(0);
  });

  it("should count the assertions of the version the run used", () => {
    versionData = { evaluators: [judge(["correct", "polite"])] };
    const { result } = renderHook(() =>
      useSuiteAssertionCount("suite-1", experimentWithVersion("abc")),
    );
    expect(result.current).toBe(2);
  });
});

describe("fetchSuiteAssertionCount", () => {
  it("should be unknown when the version can't be read, so items aren't counted as scored too early", async () => {
    const queryClient = {
      fetchQuery: vi.fn().mockRejectedValue(new Error("unavailable")),
    } as unknown as QueryClient;

    expect(
      await fetchSuiteAssertionCount(queryClient, "suite-1", "abc"),
    ).toBeUndefined();
  });

  it("should be 0 for a run without a suite version", async () => {
    const queryClient = { fetchQuery: vi.fn() } as unknown as QueryClient;

    expect(
      await fetchSuiteAssertionCount(queryClient, "suite-1", undefined),
    ).toBe(0);
    expect(queryClient.fetchQuery).not.toHaveBeenCalled();
  });
});
