import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";

import { TooltipProvider } from "@/ui/tooltip";
import OptimizationKPICards from "./OptimizationKPICards";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";

/**
 * A whole trial, not a cost-shaped stub. A cast fixture lets a new branch of the
 * card read `status` or `created_at` and silently get `undefined`, instead of
 * the test data failing at compile time.
 */
const createExperiment = (overrides: Partial<Experiment> = {}): Experiment => ({
  id: "e1",
  dataset_id: "d1",
  dataset_name: "dataset",
  type: EXPERIMENT_TYPE.TRIAL,
  status: "completed",
  name: "trial",
  trace_count: 1,
  total_estimated_cost: 0,
  created_at: "2026-07-30T10:00:00Z",
  last_updated_at: "2026-07-30T10:05:00Z",
  ...overrides,
});

const experiments = [
  createExperiment({ id: "e1", total_estimated_cost: 0.1 }),
  createExperiment({ id: "e2", total_estimated_cost: 0.05 }),
];

// The cost value carries a tooltip naming its source, so it needs the provider
// the app mounts at its root (v2/App.tsx).
const renderCards = (
  props: React.ComponentProps<typeof OptimizationKPICards>,
) =>
  render(<OptimizationKPICards {...props} />, {
    wrapper: ({ children }) => <TooltipProvider>{children}</TooltipProvider>,
  });

describe("OptimizationKPICards", () => {
  it("shows the backend total_optimization_cost when provided (OPIK-7521)", () => {
    // The server aggregate includes optimizer-internal spend (e.g. GEPA
    // reflection) that belongs to no trial, so it must win over the trial sum.
    renderCards({ experiments, totalOptimizationCost: 0.22 });

    expect(screen.getByText("Optimization cost")).toBeInTheDocument();
    expect(screen.getByText("$0.220")).toBeInTheDocument();
  });

  it("falls back to summing trial costs when the backend total is absent", () => {
    renderCards({ experiments });

    expect(screen.getByText("$0.150")).toBeInTheDocument();
  });

  it("falls back to the trial sum when the backend reports zero", () => {
    // The aggregate comes back as 0 rather than null when it has nothing to
    // report, so a bare nullish check would render "-" over a known trial cost.
    renderCards({ experiments, totalOptimizationCost: 0 });

    expect(screen.getByText("$0.150")).toBeInTheDocument();
  });

  it("renders a dash when no cost is known", () => {
    renderCards({ experiments: [] });

    expect(screen.getByText("Optimization cost")).toBeInTheDocument();
    expect(screen.getAllByText("-").length).toBeGreaterThan(0);
    expect(screen.queryByText(/^\$/)).not.toBeInTheDocument();
  });
});
