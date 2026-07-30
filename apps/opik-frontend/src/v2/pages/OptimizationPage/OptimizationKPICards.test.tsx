import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";

import OptimizationKPICards from "./OptimizationKPICards";
import { Experiment } from "@/types/datasets";

const experiments = [
  { id: "e1", total_estimated_cost: 0.1 },
  { id: "e2", total_estimated_cost: 0.05 },
] as unknown as Experiment[];

describe("OptimizationKPICards", () => {
  it("shows the backend total_optimization_cost when provided (OPIK-7521)", () => {
    // The server aggregate includes optimizer-internal spend (e.g. GEPA
    // reflection) that belongs to no trial, so it must win over the trial sum.
    render(
      <OptimizationKPICards
        experiments={experiments}
        totalOptimizationCost={0.22}
      />,
    );

    expect(screen.getByText("Optimization cost")).toBeInTheDocument();
    expect(screen.getByText("$0.220")).toBeInTheDocument();
  });

  it("falls back to summing trial costs when the backend total is absent", () => {
    render(<OptimizationKPICards experiments={experiments} />);

    expect(screen.getByText("$0.150")).toBeInTheDocument();
  });

  it("renders a dash when no cost is known", () => {
    render(<OptimizationKPICards experiments={[]} />);

    expect(screen.getByText("Optimization cost")).toBeInTheDocument();
    expect(screen.getAllByText("-").length).toBeGreaterThan(0);
    expect(screen.queryByText(/^\$/)).not.toBeInTheDocument();
  });
});
