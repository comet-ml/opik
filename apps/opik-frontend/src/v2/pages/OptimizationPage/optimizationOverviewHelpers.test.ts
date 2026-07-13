import { describe, it, expect } from "vitest";

import {
  computeEmptyRunWarning,
  getCompletedRunDurationSeconds,
  getOptimizationDurationSeconds,
  getOptimizationRefetchInterval,
} from "./optimizationOverviewHelpers";
import { AggregatedCandidate, OPTIMIZATION_STATUS } from "@/types/optimizations";
import { OPTIMIZATION_ACTIVE_REFETCH_INTERVAL } from "@/lib/optimizations";

const makeCandidate = (
  overrides: Partial<AggregatedCandidate> & {
    candidateId: string;
    stepIndex: number;
  },
): AggregatedCandidate => ({
  id: overrides.candidateId,
  parentCandidateIds: [],
  trialNumber: 1,
  score: undefined,
  runtimeCost: undefined,
  latencyP50: undefined,
  totalTraceCount: 0,
  totalDatasetItemCount: 0,
  passedCount: 0,
  totalCount: 0,
  experimentIds: [],
  name: "test",
  created_at: "2025-01-01T00:00:00Z",
  ...overrides,
});

describe("getOptimizationDurationSeconds", () => {
  it("returns the wall-clock seconds between created and end", () => {
    expect(
      getOptimizationDurationSeconds(
        "2026-01-01T00:00:00Z",
        "2026-01-01T00:05:00Z",
      ),
    ).toBe(300);
  });

  it("returns undefined when the end is not after the start", () => {
    // The old bug used the last trial's created_at as the end, which can equal
    // or precede the optimization start — that must not produce a bogus 0/negative.
    expect(
      getOptimizationDurationSeconds(
        "2026-01-01T00:05:00Z",
        "2026-01-01T00:00:00Z",
      ),
    ).toBeUndefined();
  });

  it("returns undefined when either timestamp is missing", () => {
    expect(
      getOptimizationDurationSeconds(undefined, "2026-01-01T00:05:00Z"),
    ).toBeUndefined();
    expect(
      getOptimizationDurationSeconds("2026-01-01T00:00:00Z", undefined),
    ).toBeUndefined();
  });
});

describe("getCompletedRunDurationSeconds", () => {
  const base = {
    isInProgress: false,
    optimizationCreatedAt: "2026-01-01T00:00:00Z",
    optimizationLastUpdatedAt: "2026-01-01T00:05:00Z",
    trialCreatedTimes: ["2026-01-01T00:01:00Z", "2026-01-01T00:03:00Z"],
  };

  it("uses the run's completion time as the end", () => {
    expect(getCompletedRunDurationSeconds(base)).toBe(300);
  });

  it("falls back to the newest trial's created_at when last_updated_at is missing", () => {
    expect(
      getCompletedRunDurationSeconds({
        ...base,
        optimizationLastUpdatedAt: undefined,
      }),
    ).toBe(180);
  });

  it("returns undefined while the run is in progress", () => {
    expect(
      getCompletedRunDurationSeconds({ ...base, isInProgress: true }),
    ).toBeUndefined();
  });

  it("returns undefined when the run produced no trials", () => {
    expect(
      getCompletedRunDurationSeconds({ ...base, trialCreatedTimes: [] }),
    ).toBeUndefined();
  });

  it("returns undefined when the run's start time is missing", () => {
    expect(
      getCompletedRunDurationSeconds({
        ...base,
        optimizationCreatedAt: undefined,
      }),
    ).toBeUndefined();
  });
});

describe("computeEmptyRunWarning", () => {
  it("does not warn while the run is unfinished or errored", () => {
    const candidates = [makeCandidate({ candidateId: "a", stepIndex: 0 })];
    expect(
      computeEmptyRunWarning(candidates, OPTIMIZATION_STATUS.RUNNING),
    ).toBe(false);
    expect(
      computeEmptyRunWarning(candidates, OPTIMIZATION_STATUS.INITIALIZED),
    ).toBe(false);
    expect(computeEmptyRunWarning(candidates, OPTIMIZATION_STATUS.ERROR)).toBe(
      false,
    );
    expect(computeEmptyRunWarning(candidates, undefined)).toBe(false);
  });

  it("warns on a COMPLETED run where no non-baseline trial scored", () => {
    const candidates = [
      // A scored baseline does not count — it is expected on every run.
      makeCandidate({ candidateId: "base", stepIndex: 0, score: 0.5 }),
      makeCandidate({
        candidateId: "a",
        stepIndex: 1,
        score: undefined,
        parentCandidateIds: ["base"],
      }),
      makeCandidate({
        candidateId: "b",
        stepIndex: 2,
        score: undefined,
        parentCandidateIds: ["a"],
      }),
    ];
    expect(
      computeEmptyRunWarning(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(true);
  });

  it("warns on a COMPLETED run that produced no non-baseline trials at all", () => {
    const candidates = [
      makeCandidate({ candidateId: "base", stepIndex: 0, score: 0.5 }),
    ];
    expect(
      computeEmptyRunWarning(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(true);
  });

  it("does not warn when at least one non-baseline trial scored", () => {
    const candidates = [
      makeCandidate({ candidateId: "base", stepIndex: 0, score: 0.5 }),
      makeCandidate({
        candidateId: "a",
        stepIndex: 1,
        score: undefined,
        parentCandidateIds: ["base"],
      }),
      makeCandidate({
        candidateId: "b",
        stepIndex: 1,
        score: 0.7,
        parentCandidateIds: ["base"],
      }),
    ];
    expect(
      computeEmptyRunWarning(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(false);
  });
});

describe("getOptimizationRefetchInterval", () => {
  it("polls while the run is in progress", () => {
    expect(getOptimizationRefetchInterval(OPTIMIZATION_STATUS.RUNNING)).toBe(
      OPTIMIZATION_ACTIVE_REFETCH_INTERVAL,
    );
    expect(
      getOptimizationRefetchInterval(OPTIMIZATION_STATUS.INITIALIZED),
    ).toBe(OPTIMIZATION_ACTIVE_REFETCH_INTERVAL);
  });

  it("stops polling once the run is finished", () => {
    expect(getOptimizationRefetchInterval(OPTIMIZATION_STATUS.COMPLETED)).toBe(
      false,
    );
    expect(getOptimizationRefetchInterval(OPTIMIZATION_STATUS.ERROR)).toBe(
      false,
    );
    expect(getOptimizationRefetchInterval(undefined)).toBe(false);
  });
});
