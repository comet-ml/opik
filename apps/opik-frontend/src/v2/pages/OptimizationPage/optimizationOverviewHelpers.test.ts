import { describe, it, expect } from "vitest";

import {
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
