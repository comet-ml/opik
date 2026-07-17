import { describe, it, expect } from "vitest";

import {
  getCompletedRunDurationSeconds,
  getOptimizationDurationSeconds,
  getOptimizationRefetchInterval,
} from "./optimizationOverviewHelpers";
import { OPTIMIZATION_STATUS } from "@/types/optimizations";
import { OPTIMIZATION_ACTIVE_REFETCH_INTERVAL } from "@/lib/optimizations";

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
