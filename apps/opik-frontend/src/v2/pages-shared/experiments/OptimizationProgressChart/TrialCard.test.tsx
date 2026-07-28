import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";

import TrialCard from "./TrialCard";
import { AggregatedCandidate } from "@/types/optimizations";

const candidate = {
  trialNumber: 20,
  score: 0.9,
  latencyP50: 24800,
  runtimeCost: 0.0008,
  passedCount: 9,
  totalCount: 10,
} as unknown as AggregatedCandidate;

describe("TrialCard", () => {
  it("shows the trial number, a status label in the header, and the core rows", () => {
    render(<TrialCard candidate={candidate} status="passed" stepIndex={3} />);

    expect(screen.getByText("Trial #20")).toBeInTheDocument();
    expect(screen.getByText("Passed step 3")).toBeInTheDocument();
    // Status is a header label now, not a metric row.
    expect(screen.queryByText("Status")).not.toBeInTheDocument();
    expect(screen.getByText("Score")).toBeInTheDocument();
    expect(screen.getByText("Latency")).toBeInTheDocument();
    expect(screen.getByText("Runtime cost")).toBeInTheDocument();
  });

  it("labels the header 'Best trial' when isBest", () => {
    render(
      <TrialCard candidate={candidate} status="passed" stepIndex={5} isBest />,
    );

    expect(screen.getByText("Best trial")).toBeInTheDocument();
    expect(screen.queryByText("Passed step 5")).not.toBeInTheDocument();
    expect(screen.getByText("Score")).toBeInTheDocument();
  });

  it("uses the Pass rate label for test suites", () => {
    render(
      <TrialCard
        candidate={candidate}
        status="passed"
        stepIndex={1}
        isTestSuite
      />,
    );

    expect(screen.getByText("Pass rate")).toBeInTheDocument();
    expect(screen.queryByText("Score")).not.toBeInTheDocument();
  });

  it("omits latency and cost rows when they are absent", () => {
    const sparse = {
      trialNumber: 1,
      score: 0.5,
      latencyP50: null,
      runtimeCost: null,
    } as unknown as AggregatedCandidate;

    render(<TrialCard candidate={sparse} status="pruned" stepIndex={2} />);

    expect(screen.getByText("Discarded in step 2")).toBeInTheDocument();
    expect(screen.queryByText("Latency")).not.toBeInTheDocument();
    expect(screen.queryByText("Runtime cost")).not.toBeInTheDocument();
  });
});
