import { describe, it, expect } from "vitest";
import {
  computeCandidateStatuses,
  buildCandidateChartData,
} from "./optimizationChartUtils";
import { AggregatedCandidate } from "@/types/optimizations";

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

  describe("non-evaluation-suite", () => {
    it("should mark all scored candidates as passed", () => {
      const candidates = [
        makeCandidate({ candidateId: "a", stepIndex: 0, score: 0.5 }),
        makeCandidate({
          candidateId: "b",
          stepIndex: 1,
          score: 0.3,
          parentCandidateIds: ["a"],
        }),
      ];
      const result = computeCandidateStatuses(candidates, false);
      expect(result.get("b")).toBe("passed");
    });
  });

  describe("in-progress evaluation suite", () => {
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

  describe("completed evaluation suite", () => {
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
