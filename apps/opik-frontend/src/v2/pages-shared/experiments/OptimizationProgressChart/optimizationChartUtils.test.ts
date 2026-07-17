import { describe, it, expect } from "vitest";
import {
  computeCandidateStatuses,
  buildCandidateChartData,
  buildTrendLineEdges,
  buildTrialCardModel,
  buildEdgePath,
  getUniqueSteps,
  findNearestDot,
  getTrialStatusLabel,
  getTrialDotColor,
  TRIAL_STATUS_COLORS,
  TRIAL_BEST_COLOR,
  TRIAL_BEST_RING_COLOR,
  CandidateDataPoint,
} from "./optimizationChartUtils";
import { AggregatedCandidate } from "@/types/optimizations";

const makePoint = (
  overrides: Partial<CandidateDataPoint> & {
    candidateId: string;
    stepIndex: number;
  },
): CandidateDataPoint => ({
  parentCandidateIds: [],
  value: null,
  status: "passed",
  name: "test",
  ...overrides,
});

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

describe("computeCandidateStatuses", () => {
  describe("baseline", () => {
    it("should mark step 0 as baseline", () => {
      const candidates = [makeCandidate({ candidateId: "a", stepIndex: 0 })];
      const result = computeCandidateStatuses(candidates);
      expect(result.get("a")).toBe("baseline");
    });
  });

  describe("running", () => {
    it("should mark unscored candidate as running", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates);
      expect(result.get("b")).toBe("running");
    });
  });

  describe("non-test-suite", () => {
    it("derives passed/pruned from the tree, same as a test suite", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.3 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.9,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.2,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, false);
      expect(result.get("a")).toBe("baseline");
      expect(result.get("b")).toBe("passed"); // best-scoring winner
      expect(result.get("c")).toBe("pruned"); // discarded sibling → faded dot
    });
  });

  describe("in-progress test suite", () => {
    it("should mark best candidate as passed", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.3,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true);
      expect(result.get("b")).toBe("passed");
    });

    it("should mark candidate with children as passed", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.6,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 2,
          score: 0.7,
          parentCandidateIds: ["b"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true);
      expect(result.get("b")).toBe("passed");
    });

    it("should mark candidate with score < best as pruned", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.3,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true);
      expect(result.get("c")).toBe("pruned");
    });

    it("should mark sibling as pruned when another sibling has children", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "d",
          stepIndex: 2,
          score: 0.9,
          parentCandidateIds: ["b"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true);
      expect(result.get("b")).toBe("passed");
      expect(result.get("c")).toBe("pruned");
    });

    it("should mark scored candidate as evaluating when tied with best and no sibling has children", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
          created_at: "2025-01-02",
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
          created_at: "2025-01-03",
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true);
      // b is best (earliest creation among ties) → passed
      expect(result.get("b")).toBe("passed");
      // c ties with best but is not best → evaluating (no sibling has children)
      expect(result.get("c")).toBe("evaluating");
    });

    it("should treat ghost parent as having children", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true, {
        candidateId: "ghost",
        stepIndex: 2,
        parentCandidateIds: ["b"],
      });
      expect(result.get("b")).toBe("passed");
      expect(result.get("c")).toBe("pruned");
    });

    it("should not prune candidates at same step but different parents", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.8,
          parentCandidateIds: ["a"],
        }),
        // d and e are at step 2 but from different parents
        makeCandidate({
          candidateId: "d",
          stepIndex: 2,
          score: 0.8,
          parentCandidateIds: ["b"],
        }),
        makeCandidate({
          candidateId: "e",
          stepIndex: 2,
          score: 0.8,
          parentCandidateIds: ["c"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, true);
      // d has no children from its branch, e has no children from its branch
      // They are NOT siblings (different parents), so neither should prune the other
      // Both should be evaluating (tied with best, no sibling with children)
      expect(result.get("d")).toBe("evaluating");
      expect(result.get("e")).toBe("evaluating");
    });
  });

  describe("completed test suite", () => {
    it("should mark candidate with descendants as passed", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.6,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 2,
          score: 0.7,
          parentCandidateIds: ["b"],
        }),
        makeCandidate({
          candidateId: "d",
          stepIndex: 2,
          score: 0.3,
          parentCandidateIds: ["b"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, false);
      expect(result.get("b")).toBe("passed");
      expect(result.get("d")).toBe("pruned");
    });

    it("should mark best candidate as passed even without descendants", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.9,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 1,
          score: 0.3,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, false);
      expect(result.get("b")).toBe("passed");
      expect(result.get("c")).toBe("pruned");
    });

    it("should mark transitive ancestors as passed", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.6,
          parentCandidateIds: ["a"],
        }),
        makeCandidate({
          candidateId: "c",
          stepIndex: 2,
          score: 0.7,
          parentCandidateIds: ["b"],
        }),
        makeCandidate({
          candidateId: "d",
          stepIndex: 3,
          score: 0.8,
          parentCandidateIds: ["c"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, true, false);
      expect(result.get("b")).toBe("passed");
      expect(result.get("c")).toBe("passed");
    });
  });
});

describe("buildCandidateChartData", () => {
  it("should sort by stepIndex then created_at", () => {
    const candidates = [
      makeCandidate({
        candidateId: "b",
        stepIndex: 1,
        score: 0.5,
        created_at: "2025-01-02",
        parentCandidateIds: ["a"],
      }),
      makeCandidate({
        candidateId: "a",
        stepIndex: 0,
        score: 0.3,
        created_at: "2025-01-01",
      }),
    ];
    const data = buildCandidateChartData(candidates);
    expect(data[0].candidateId).toBe("a");
    expect(data[1].candidateId).toBe("b");
  });

  it("should include status from computeCandidateStatuses", () => {
    const candidates = [
      makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
    ];
    const data = buildCandidateChartData(candidates);
    expect(data[0].status).toBe("baseline");
  });
});

describe("getTrialStatusLabel", () => {
  it("labels baseline without a step suffix", () => {
    expect(getTrialStatusLabel("baseline", 0)).toBe("Baseline");
  });

  it("labels passed and pruned trials with their step", () => {
    expect(getTrialStatusLabel("passed", 1)).toBe("Passed step 1");
    expect(getTrialStatusLabel("pruned", 2)).toBe("Discarded in step 2");
  });
});

describe("getTrialDotColor", () => {
  it("gives the best trial its own colour regardless of status or run type", () => {
    expect(
      getTrialDotColor({ status: "pruned", isBest: true, isTestSuite: true }),
    ).toBe(TRIAL_BEST_COLOR);
    expect(
      getTrialDotColor({ status: "passed", isBest: true, isTestSuite: false }),
    ).toBe(TRIAL_BEST_COLOR);
  });

  it("colours every status for test-suite runs", () => {
    expect(
      getTrialDotColor({
        status: "evaluating",
        isBest: false,
        isTestSuite: true,
      }),
    ).toBe(TRIAL_STATUS_COLORS.evaluating);
    expect(
      getTrialDotColor({ status: "pruned", isBest: false, isTestSuite: true }),
    ).toBe(TRIAL_STATUS_COLORS.pruned);
  });

  it("collapses dataset runs to discarded vs passed", () => {
    expect(
      getTrialDotColor({ status: "pruned", isBest: false, isTestSuite: false }),
    ).toBe(TRIAL_STATUS_COLORS.pruned);
    expect(
      getTrialDotColor({
        status: "evaluating",
        isBest: false,
        isTestSuite: false,
      }),
    ).toBe(TRIAL_STATUS_COLORS.passed);
  });
});

describe("getUniqueSteps", () => {
  it("returns sorted, de-duplicated step indices", () => {
    expect(
      getUniqueSteps([
        { stepIndex: 2 },
        { stepIndex: 0 },
        { stepIndex: 2 },
        { stepIndex: 1 },
      ]),
    ).toEqual([0, 1, 2]);
  });

  it("returns an empty array for no items", () => {
    expect(getUniqueSteps([])).toEqual([]);
  });
});

describe("buildEdgePath", () => {
  it("draws a horizontal-control-point cubic bezier between two dots", () => {
    // Control points sit at the midpoint x, level with each endpoint's y.
    expect(buildEdgePath({ cx: 0, cy: 0 }, { cx: 10, cy: 20 })).toBe(
      "M 0,0 C 5,0 5,20 10,20",
    );
  });
});

describe("buildTrendLineEdges", () => {
  it("connects the best passed trial of each step into a single path", () => {
    const edges = buildTrendLineEdges([
      makePoint({
        candidateId: "base",
        stepIndex: 0,
        value: 0.2,
        status: "baseline",
      }),
      makePoint({ candidateId: "s1-low", stepIndex: 1, value: 0.4 }),
      makePoint({ candidateId: "s1-high", stepIndex: 1, value: 0.6 }),
      makePoint({ candidateId: "s2", stepIndex: 2, value: 0.8 }),
    ]);

    // One line, no fork: baseline -> step 1 winner -> step 2.
    expect(edges).toEqual([
      { parentCandidateId: "base", childCandidateId: "s1-high" },
      { parentCandidateId: "s1-high", childCandidateId: "s2" },
    ]);
  });

  it("skips discarded, evaluating, and unscored trials", () => {
    const edges = buildTrendLineEdges([
      makePoint({
        candidateId: "base",
        stepIndex: 0,
        value: 0.2,
        status: "baseline",
      }),
      // Step 1 only has a discarded trial — the line bridges over it.
      makePoint({
        candidateId: "s1-pruned",
        stepIndex: 1,
        value: 0.9,
        status: "pruned",
      }),
      makePoint({
        candidateId: "s2-evaluating",
        stepIndex: 2,
        value: 0.7,
        status: "evaluating",
      }),
      makePoint({ candidateId: "s2-unscored", stepIndex: 2, value: null }),
      makePoint({ candidateId: "s2", stepIndex: 2, value: 0.5 }),
    ]);

    expect(edges).toEqual([
      { parentCandidateId: "base", childCandidateId: "s2" },
    ]);
  });

  it("returns no edges when fewer than two steps have a winner", () => {
    expect(buildTrendLineEdges([])).toEqual([]);
    expect(
      buildTrendLineEdges([
        makePoint({
          candidateId: "base",
          stepIndex: 0,
          value: 0.2,
          status: "baseline",
        }),
      ]),
    ).toEqual([]);
  });
});

describe("buildTrialCardModel", () => {
  it("builds the title, status label, dot colour, and metric rows", () => {
    const candidate = makeCandidate({
      candidateId: "a",
      stepIndex: 3,
      trialNumber: 20,
      score: 0.9,
      latencyP50: 24800,
      runtimeCost: 0.0008,
    });

    const model = buildTrialCardModel({
      candidate,
      status: "passed",
      stepIndex: 3,
    });

    expect(model.title).toBe("Trial #20");
    expect(model.statusLabel).toBe("Passed step 3");
    expect(model.dotColor).toBe(TRIAL_STATUS_COLORS.passed);
    expect(model.dotRingColor).toBeUndefined();
    expect(model.rows.map((r) => r.label)).toEqual([
      "Score",
      "Latency",
      "Runtime cost",
    ]);
  });

  it("labels and colours the best trial, with a ring around the dot", () => {
    const candidate = makeCandidate({
      candidateId: "a",
      stepIndex: 5,
      score: 0.8,
    });

    const model = buildTrialCardModel({
      candidate,
      status: "passed",
      stepIndex: 5,
      isBest: true,
    });

    expect(model.statusLabel).toBe("Best trial");
    expect(model.dotColor).toBe(TRIAL_BEST_COLOR);
    expect(model.dotRingColor).toBe(TRIAL_BEST_RING_COLOR);
  });

  it("uses Pass rate with a fraction for test suites", () => {
    const candidate = makeCandidate({
      candidateId: "a",
      stepIndex: 1,
      score: 0.9,
      passedCount: 9,
      totalCount: 10,
    });

    const model = buildTrialCardModel({
      candidate,
      status: "passed",
      stepIndex: 1,
      isTestSuite: true,
    });

    const scoreRow = model.rows[0];
    expect(scoreRow.label).toBe("Pass rate");
    expect(scoreRow.value).toContain("(9/10)");
  });

  it("omits latency and cost rows when absent, and shows '-' for no score", () => {
    const candidate = makeCandidate({
      candidateId: "a",
      stepIndex: 2,
      score: undefined,
      latencyP50: undefined,
      runtimeCost: undefined,
    });

    const model = buildTrialCardModel({
      candidate,
      status: "pruned",
      stepIndex: 2,
    });

    expect(model.rows).toHaveLength(1);
    expect(model.rows[0]).toEqual({ label: "Score", value: "-" });
    expect(model.statusLabel).toBe("Discarded in step 2");
  });
});

describe("findNearestDot", () => {
  const positions: Array<[string, { cx: number; cy: number }]> = [
    ["a", { cx: 10, cy: 10 }],
    ["b", { cx: 40, cy: 12 }],
    ["c", { cx: 42, cy: 30 }],
  ];

  it("returns the closest dot within maxDistance", () => {
    expect(findNearestDot(positions, 12, 11, 22)?.candidateId).toBe("a");
    expect(findNearestDot(positions, 41, 28, 22)?.candidateId).toBe("c");
  });

  it("returns null when nothing is within maxDistance", () => {
    expect(findNearestDot(positions, 200, 200, 22)).toBeNull();
    expect(findNearestDot([], 10, 10, 22)).toBeNull();
  });

  it("picks a single winner among overlapping clustered dots (no thrash)", () => {
    // b and c sit ~18px apart — both inside a 22px radius from (41,20). Exactly
    // one is returned (the nearer, b), which is what kills the flicker.
    const hit = findNearestDot(positions, 41, 20, 22);
    expect(hit?.candidateId).toBe("b");
  });

  it("resolves ties to the last (topmost-drawn) dot", () => {
    const tie: Array<[string, { cx: number; cy: number }]> = [
      ["under", { cx: 0, cy: 0 }],
      ["over", { cx: 0, cy: 0 }],
    ];
    expect(findNearestDot(tie, 0, 0, 22)?.candidateId).toBe("over");
  });
});
