import { describe, it, expect } from "vitest";

import {
  EMPTY_RUN_CAUSE,
  computeEmptyRunCause,
  getCompletedRunDurationSeconds,
  getEmptyRunKPICaption,
  getEmptyRunMessage,
  getEmptyRunTitle,
  getOptimizationDurationSeconds,
  getOptimizationRefetchInterval,
} from "./optimizationOverviewHelpers";
import {
  AggregatedCandidate,
  OPTIMIZATION_STATUS,
  OptimizationScoringHealth,
} from "@/types/optimizations";
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

describe("computeEmptyRunCause", () => {
  it("stays NONE while the run is unfinished or errored", () => {
    const candidates = [makeCandidate({ candidateId: "a", stepIndex: 0 })];
    expect(computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.RUNNING)).toBe(
      EMPTY_RUN_CAUSE.NONE,
    );
    expect(
      computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.INITIALIZED),
    ).toBe(EMPTY_RUN_CAUSE.NONE);
    expect(computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.ERROR)).toBe(
      EMPTY_RUN_CAUSE.NONE,
    );
    expect(computeEmptyRunCause(candidates, undefined)).toBe(
      EMPTY_RUN_CAUSE.NONE,
    );
  });

  it("reports SCORING_FAILED when candidates ran but none scored", () => {
    const candidates = [
      // A scored baseline does not count; it is expected on every run.
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
      computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(EMPTY_RUN_CAUSE.SCORING_FAILED);
  });

  it("reports NO_CANDIDATES when the baseline scored but nothing else was generated", () => {
    // The OPIK-7458 case: a strong seed prompt, so the metric demonstrably worked.
    const candidates = [
      makeCandidate({ candidateId: "base", stepIndex: 0, score: 1 }),
    ];
    expect(
      computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(EMPTY_RUN_CAUSE.NO_CANDIDATES);
  });

  it("reports SCORING_FAILED when nothing was generated AND the baseline never scored", () => {
    const candidates = [
      makeCandidate({ candidateId: "base", stepIndex: 0, score: undefined }),
    ];
    expect(
      computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(EMPTY_RUN_CAUSE.SCORING_FAILED);
  });

  it("reports SCORING_FAILED for a COMPLETED run with no candidate rows at all", () => {
    expect(computeEmptyRunCause([], OPTIMIZATION_STATUS.COMPLETED)).toBe(
      EMPTY_RUN_CAUSE.SCORING_FAILED,
    );
  });

  it("stays NONE when at least one non-baseline trial scored", () => {
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
      computeEmptyRunCause(candidates, OPTIMIZATION_STATUS.COMPLETED),
    ).toBe(EMPTY_RUN_CAUSE.NONE);
  });
});

describe("getEmptyRunTitle", () => {
  it("names the cause instead of always claiming there are no scores", () => {
    expect(getEmptyRunTitle(EMPTY_RUN_CAUSE.NO_CANDIDATES)).toBe(
      "No candidates generated",
    );
    expect(getEmptyRunTitle(EMPTY_RUN_CAUSE.SCORING_FAILED)).toBe(
      "No usable scores",
    );
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

// ---------------------------------------------------------------------------
// getEmptyRunMessage: Wave 2 exact-count path + Wave-1 fallback
// ---------------------------------------------------------------------------

describe("getEmptyRunMessage", () => {
  const FAILED = EMPTY_RUN_CAUSE.SCORING_FAILED;

  it("says nothing when there is no empty-run cause", () => {
    expect(getEmptyRunMessage(EMPTY_RUN_CAUSE.NONE)).toBeNull();
    expect(
      getEmptyRunMessage(EMPTY_RUN_CAUSE.NONE, {
        failed_count: 5,
        total_count: 5,
      }),
    ).toBeNull();
  });

  // --- NO_CANDIDATES (OPIK-7458) ---

  it("names the optimizer, not the metric, when no candidates were generated", () => {
    const msg = getEmptyRunMessage(EMPTY_RUN_CAUSE.NO_CANDIDATES);
    expect(msg).toContain("produced no prompt variants to score");
    expect(msg).toContain("the baseline prompt was kept");
    expect(msg).not.toContain("failed");
    expect(msg).not.toContain("run it again");
  });

  it("ignores scoring_health for NO_CANDIDATES (the baseline scored, so counts can't explain it)", () => {
    // The regression from OPIK-7458: a degenerate {0, 0} health object used to
    // route this run into the metric-failure copy via the `total_count > 0` guard.
    const degenerate: OptimizationScoringHealth = {
      failed_count: 0,
      total_count: 0,
    };
    const populated: OptimizationScoringHealth = {
      failed_count: 0,
      total_count: 30,
    };
    const expected = getEmptyRunMessage(EMPTY_RUN_CAUSE.NO_CANDIDATES);
    expect(getEmptyRunMessage(EMPTY_RUN_CAUSE.NO_CANDIDATES, degenerate)).toBe(
      expected,
    );
    expect(getEmptyRunMessage(EMPTY_RUN_CAUSE.NO_CANDIDATES, populated)).toBe(
      expected,
    );
  });

  // --- Exact-count path (scoring_health present) ---

  it("uses all-failed framing when every item failed (plural)", () => {
    const health: OptimizationScoringHealth = {
      failed_count: 5,
      total_count: 5,
    };
    const msg = getEmptyRunMessage(FAILED, health);
    expect(msg).toContain("All 5 items failed to score");
  });

  it("uses all-failed framing when every item failed (singular — 1 item)", () => {
    const health: OptimizationScoringHealth = {
      failed_count: 1,
      total_count: 1,
    };
    const msg = getEmptyRunMessage(FAILED, health);
    // A one-item dataset reads "The item failed …", never "All 1 item …".
    expect(msg).toContain("The item failed to score");
    expect(msg).not.toContain("All 1");
    expect(msg).not.toContain("1 items");
  });

  it("uses partial framing when some items failed", () => {
    const health: OptimizationScoringHealth = {
      failed_count: 3,
      total_count: 10,
    };
    const msg = getEmptyRunMessage(FAILED, health);
    expect(msg).toContain("3 of 10 items failed to score");
    // Partial framing should NOT say "All"
    expect(msg).not.toMatch(/^All /);
  });

  it("uses plural 'items' for a partial single failure (noun agrees with total)", () => {
    const health: OptimizationScoringHealth = {
      failed_count: 1,
      total_count: 10,
    };
    const msg = getEmptyRunMessage(FAILED, health);
    // "1 of 10 items" — the noun agrees with the total, not the failed count.
    expect(msg).toContain("1 of 10 items failed to score");
  });

  it("returns null when failed_count is 0 (nothing failed)", () => {
    const health: OptimizationScoringHealth = {
      failed_count: 0,
      total_count: 10,
    };
    expect(getEmptyRunMessage(FAILED, health)).toBeNull();
  });

  it("falls back to the heuristic message when total_count is 0 (degenerate health object)", () => {
    // total_count === 0 → the exact-count branch is skipped and heuristic is used.
    const health: OptimizationScoringHealth = {
      failed_count: 0,
      total_count: 0,
    };
    // With total_count === 0, the guard `total_count > 0` is false, so we
    // return the heuristic fallback (non-null).
    const msg = getEmptyRunMessage(FAILED, health);
    expect(msg).not.toBeNull();
    expect(msg).toContain("no usable scores");
  });

  // --- Heuristic fallback (absent scoring_health) ---

  it("returns the Wave-1 heuristic copy when scoring_health is absent", () => {
    const msg = getEmptyRunMessage(FAILED, undefined);
    // The static Wave-1 message must be returned unchanged.
    expect(msg).toBe(
      "This run finished but produced no usable scores — the metric may have failed on every item. Open the logs, check the metric and model, then run it again.",
    );
  });
});

// ---------------------------------------------------------------------------
// getEmptyRunKPICaption: compact caption for the KPI score card
// ---------------------------------------------------------------------------

describe("getEmptyRunKPICaption", () => {
  const FAILED = EMPTY_RUN_CAUSE.SCORING_FAILED;

  it("returns null when the run has no empty-run cause", () => {
    expect(getEmptyRunKPICaption(EMPTY_RUN_CAUSE.NONE)).toBeNull();
    expect(
      getEmptyRunKPICaption(EMPTY_RUN_CAUSE.NONE, {
        failed_count: 5,
        total_count: 5,
      }),
    ).toBeNull();
  });

  it("captions a no-candidates run neutrally, since the score shown is the baseline's", () => {
    const caption = getEmptyRunKPICaption(EMPTY_RUN_CAUSE.NO_CANDIDATES);
    expect(caption).toBe("No candidates generated. Baseline prompt kept.");
    expect(caption).not.toContain("failed");
  });

  it("all-failed exact count caption (plural)", () => {
    const caption = getEmptyRunKPICaption(FAILED, {
      failed_count: 8,
      total_count: 8,
    });
    expect(caption).toContain("All 8 items failed to score");
    expect(caption).toContain("check the logs");
  });

  it("all-failed exact count caption (singular)", () => {
    const caption = getEmptyRunKPICaption(FAILED, {
      failed_count: 1,
      total_count: 1,
    });
    expect(caption).toContain("The item failed to score");
    expect(caption).not.toContain("All 1");
    expect(caption).not.toContain("1 items");
  });

  it("partial failure exact count caption", () => {
    const caption = getEmptyRunKPICaption(FAILED, {
      failed_count: 4,
      total_count: 12,
    });
    expect(caption).toContain("4 of 12 items failed to score");
    expect(caption).toContain("check the logs");
  });

  it("returns null when failed_count is 0", () => {
    expect(
      getEmptyRunKPICaption(FAILED, { failed_count: 0, total_count: 10 }),
    ).toBeNull();
  });

  it("falls back to Wave-1 heuristic copy when scoring_health is absent", () => {
    const caption = getEmptyRunKPICaption(FAILED, undefined);
    expect(caption).toBe("No usable scores — check the logs.");
  });
});
