import { describe, it, expect } from "vitest";
import { sortCandidates } from "@/lib/optimizations";
import { AggregatedCandidate } from "@/types/optimizations";

const makeCandidate = (
  overrides: Partial<AggregatedCandidate> = {},
): AggregatedCandidate => ({
  id: "c-1",
  candidateId: "c-1",
  stepIndex: 0,
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
  name: "trial-1",
  created_at: "2025-01-01T00:00:00Z",
  ...overrides,
});

describe("sortCandidates", () => {
  it("should return candidates unchanged when no sort columns", () => {
    const candidates = [
      makeCandidate({ id: "a", trialNumber: 2 }),
      makeCandidate({ id: "b", trialNumber: 1 }),
    ];
    const result = sortCandidates(candidates, []);
    expect(result.map((c) => c.id)).toEqual(["a", "b"]);
  });

  it("should return candidates unchanged for unknown column", () => {
    const candidates = [
      makeCandidate({ id: "a", trialNumber: 2 }),
      makeCandidate({ id: "b", trialNumber: 1 }),
    ];
    const result = sortCandidates(candidates, [
      { id: "unknown_column", desc: false },
    ]);
    expect(result.map((c) => c.id)).toEqual(["a", "b"]);
  });

  describe("numeric sorting", () => {
    it("should sort by score ascending", () => {
      const candidates = [
        makeCandidate({ id: "a", score: 0.8 }),
        makeCandidate({ id: "b", score: 0.3 }),
        makeCandidate({ id: "c", score: 0.6 }),
      ];
      const result = sortCandidates(candidates, [
        { id: "objective_name", desc: false },
      ]);
      expect(result.map((c) => c.id)).toEqual(["b", "c", "a"]);
    });

    it("should sort by score descending", () => {
      const candidates = [
        makeCandidate({ id: "a", score: 0.3 }),
        makeCandidate({ id: "b", score: 0.8 }),
        makeCandidate({ id: "c", score: 0.6 }),
      ];
      const result = sortCandidates(candidates, [
        { id: "objective_name", desc: true },
      ]);
      expect(result.map((c) => c.id)).toEqual(["b", "c", "a"]);
    });

    it("should sort by latency", () => {
      const candidates = [
        makeCandidate({ id: "a", latencyP50: 5.0 }),
        makeCandidate({ id: "b", latencyP50: 1.0 }),
        makeCandidate({ id: "c", latencyP50: 3.0 }),
      ];
      const result = sortCandidates(candidates, [
        { id: "latency", desc: false },
      ]);
      expect(result.map((c) => c.id)).toEqual(["b", "c", "a"]);
    });

    it("should sort by runtime cost", () => {
      const candidates = [
        makeCandidate({ id: "a", runtimeCost: 0.5 }),
        makeCandidate({ id: "b", runtimeCost: 0.1 }),
      ];
      const result = sortCandidates(candidates, [
        { id: "runtime_cost", desc: false },
      ]);
      expect(result.map((c) => c.id)).toEqual(["b", "a"]);
    });

    it("should sort by step index", () => {
      const candidates = [
        makeCandidate({ id: "a", stepIndex: 3 }),
        makeCandidate({ id: "b", stepIndex: 1 }),
        makeCandidate({ id: "c", stepIndex: 2 }),
      ];
      const result = sortCandidates(candidates, [{ id: "step", desc: false }]);
      expect(result.map((c) => c.id)).toEqual(["b", "c", "a"]);
    });
  });

  describe("string sorting", () => {
    it("should sort by created_at ascending", () => {
      const candidates = [
        makeCandidate({ id: "a", created_at: "2025-03-01T00:00:00Z" }),
        makeCandidate({ id: "b", created_at: "2025-01-01T00:00:00Z" }),
        makeCandidate({ id: "c", created_at: "2025-02-01T00:00:00Z" }),
      ];
      const result = sortCandidates(candidates, [
        { id: "created_at", desc: false },
      ]);
      expect(result.map((c) => c.id)).toEqual(["b", "c", "a"]);
    });

    it("should sort by created_at descending", () => {
      const candidates = [
        makeCandidate({ id: "a", created_at: "2025-01-01T00:00:00Z" }),
        makeCandidate({ id: "b", created_at: "2025-03-01T00:00:00Z" }),
      ];
      const result = sortCandidates(candidates, [
        { id: "created_at", desc: true },
      ]);
      expect(result.map((c) => c.id)).toEqual(["b", "a"]);
    });
  });

  describe("null handling", () => {
    it("should sort nulls to the end in ascending order", () => {
      const candidates = [
        makeCandidate({ id: "a", score: undefined }),
        makeCandidate({ id: "b", score: 0.5 }),
        makeCandidate({ id: "c", score: 0.3 }),
      ];
      const result = sortCandidates(candidates, [
        { id: "objective_name", desc: false },
      ]);
      expect(result.map((c) => c.id)).toEqual(["c", "b", "a"]);
    });

    it("should sort nulls to the end in descending order", () => {
      const candidates = [
        makeCandidate({ id: "a", score: undefined }),
        makeCandidate({ id: "b", score: 0.5 }),
        makeCandidate({ id: "c", score: 0.8 }),
      ];
      const result = sortCandidates(candidates, [
        { id: "objective_name", desc: true },
      ]);
      expect(result.map((c) => c.id)).toEqual(["c", "b", "a"]);
    });

    it("should keep both-null items in original order", () => {
      const candidates = [
        makeCandidate({ id: "a", score: undefined }),
        makeCandidate({ id: "b", score: undefined }),
      ];
      const result = sortCandidates(candidates, [
        { id: "objective_name", desc: false },
      ]);
      expect(result.map((c) => c.id)).toEqual(["a", "b"]);
    });
  });

  it("should not mutate the original array", () => {
    const candidates = [
      makeCandidate({ id: "a", score: 0.8 }),
      makeCandidate({ id: "b", score: 0.3 }),
    ];
    const original = [...candidates];
    sortCandidates(candidates, [{ id: "objective_name", desc: false }]);
    expect(candidates.map((c) => c.id)).toEqual(original.map((c) => c.id));
  });
});
