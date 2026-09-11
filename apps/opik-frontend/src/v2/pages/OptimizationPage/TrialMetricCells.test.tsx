import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import { AggregatedCandidate } from "@/types/optimizations";
import { type TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";
import { TooltipProvider } from "@/ui/tooltip";
import { TrialAccuracyCell } from "./TrialMetricCells";

const makeCandidate = (
  overrides: Partial<AggregatedCandidate> & { candidateId: string },
): AggregatedCandidate => ({
  id: overrides.candidateId,
  stepIndex: 1,
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

/**
 * Minimal CellContext stand-in: TrialAccuracyCell only reads
 * `row.original`, `column.columnDef.meta` and `table.options.meta`.
 */
const makeContext = (
  row: AggregatedCandidate,
  custom: Record<string, unknown>,
) =>
  ({
    row: { original: row },
    column: { columnDef: { meta: { custom } } },
    table: { options: { meta: {} } },
  }) as unknown as CellContext<AggregatedCandidate, unknown>;

// The value span is wrapped in a Tooltip, which needs a provider in scope.
const renderCell = (
  row: AggregatedCandidate,
  custom: Record<string, unknown>,
) =>
  render(
    <TooltipProvider>
      <TrialAccuracyCell {...makeContext(row, custom)} />
    </TooltipProvider>,
  );

// OPIK-7460: mid-evaluation every metric is an average over the items scored so
// far, so a delta against the fully evaluated baseline compares different
// denominators. The provisional value stays; the comparison must not render.
describe("TrialAccuracyCell in-progress delta suppression", () => {
  const baselineCandidate = makeCandidate({
    candidateId: "baseline",
    stepIndex: 0,
    score: 0.8,
    totalDatasetItemCount: 30,
  });

  it("hides the baseline delta while the trial is still evaluating", () => {
    const row = makeCandidate({
      candidateId: "cand-b",
      score: 0.2,
      totalDatasetItemCount: 5,
    });
    const statusMap = new Map<string, TrialStatus>([["cand-b", "evaluating"]]);

    renderCell(row, { baselineCandidate, statusMap });

    // The provisional score is still shown...
    expect(screen.getByText("20%")).toBeTruthy();
    // ...but not the -75% delta against the 30-item baseline.
    expect(screen.queryByText(/75/)).toBeNull();
  });

  it("hides the delta for an unscored running trial too", () => {
    const row = makeCandidate({ candidateId: "cand-c", score: 0.1 });
    const statusMap = new Map<string, TrialStatus>([["cand-c", "running"]]);

    renderCell(row, { baselineCandidate, statusMap });

    expect(screen.queryByText(/%\s*$/)).not.toBeNull();
    expect(screen.queryByText(/87/)).toBeNull();
  });

  it("still shows the delta once the trial has a final status", () => {
    const row = makeCandidate({
      candidateId: "cand-a",
      score: 0.9,
      totalDatasetItemCount: 30,
    });
    const statusMap = new Map<string, TrialStatus>([["cand-a", "passed"]]);

    renderCell(row, { baselineCandidate, statusMap });

    expect(screen.getByText("90%")).toBeTruthy();
    // 0.9 vs the 0.8 baseline → a visible positive delta.
    expect(screen.getByText(/12/)).toBeTruthy();
  });

  it("leaves the delta intact when no statusMap is supplied", () => {
    const row = makeCandidate({
      candidateId: "cand-a",
      score: 0.9,
      totalDatasetItemCount: 30,
    });

    renderCell(row, { baselineCandidate });

    expect(screen.getByText(/12/)).toBeTruthy();
  });
});
