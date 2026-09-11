import { describe, it, expect } from "vitest";

import {
  DEFAULT_COLUMNS,
  DEFAULT_SELECTED_COLUMNS,
  DEFAULT_COLUMNS_ORDER,
} from "./OptimizationsColumns";
import { OptimizationObjectiveScoreCell } from "./OptimizationMetricCells";
import { Optimization } from "@/types/optimizations";

const OBJECTIVE_COLUMN_ID = "accuracy";

const objectiveColumn = () =>
  DEFAULT_COLUMNS.find((c) => c.id === OBJECTIVE_COLUMN_ID);

const makeRun = (overrides: Partial<Optimization> = {}): Optimization =>
  ({
    id: "opt-1",
    objective_name: "equals",
    ...overrides,
  }) as unknown as Optimization;

describe("optimization runs list columns", () => {
  // The reported bug: "Pass rate" and "Accuracy" were mutually exclusive — each
  // rendered a literal "-" for the run type it did not handle — so every Studio
  // dataset run carried a permanently empty column next to the real score.
  it("exposes a single objective-score column, not a Pass rate / Accuracy pair", () => {
    const scoreColumns = DEFAULT_COLUMNS.filter(
      (c) => c.cell === (OptimizationObjectiveScoreCell as never),
    );

    expect(scoreColumns).toHaveLength(1);
    expect(scoreColumns[0].id).toBe(OBJECTIVE_COLUMN_ID);
    expect(DEFAULT_COLUMNS.some((c) => c.id === "pass_rate")).toBe(false);
  });

  it("drops the retired pass_rate id from the default selection and order", () => {
    expect(DEFAULT_SELECTED_COLUMNS).not.toContain("pass_rate");
    expect(DEFAULT_COLUMNS_ORDER).not.toContain("pass_rate");
  });

  // Deliberate: the merged column keeps the pre-existing "accuracy" id because
  // that id is already in existing users' saved selected-columns state. A rename
  // would leave the id absent from that saved state, so the column would render
  // HIDDEN for every existing user — and forcing it visible means bumping
  // SELECTED_COLUMNS_KEY and resetting everyone's column customizations.
  // Renaming it for semantic tidiness is therefore a user-visible regression.
  it("keeps the objective column's saved-state id stable", () => {
    expect(objectiveColumn()).toBeDefined();
    expect(DEFAULT_SELECTED_COLUMNS).toContain(OBJECTIVE_COLUMN_ID);
    expect(DEFAULT_COLUMNS_ORDER).toContain(OBJECTIVE_COLUMN_ID);
  });

  // The column must carry a value for BOTH run types. This is what makes
  // run-type-dependent column filtering unnecessary — the page used to drop this
  // column when no dataset run was present, which after the merge stripped the
  // only score column from a test-suite-only page.
  describe("objective accessor covers both run types", () => {
    it("uses the best objective score for a test-suite run", () => {
      const row = makeRun({
        experiment_scores: [{ name: "pass_rate", value: 0.8 }],
        best_objective_score: 0.8,
      });

      expect(objectiveColumn()?.accessorFn?.(row)).toBe(0.8);
    });

    it("uses the objective feedback score for a dataset run", () => {
      const row = makeRun({
        experiment_scores: [],
        feedback_scores: [{ name: "equals", value: 0.42 }],
      });

      expect(objectiveColumn()?.accessorFn?.(row)).toMatchObject({
        name: "equals",
        value: 0.42,
      });
    });

    it("does not throw when a run carries no scores at all", () => {
      expect(() => objectiveColumn()?.accessorFn?.(makeRun())).not.toThrow();
    });
  });
});
