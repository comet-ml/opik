import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";

import TrialStatusCard from "./TrialStatusCard";

describe("TrialStatusCard", () => {
  it("shows the status label, step tag, and created date", () => {
    render(
      <TrialStatusCard
        status="pruned"
        stepIndex={2}
        createdAt="2026-02-04T14:42:00Z"
      />,
    );
    expect(screen.getByText("Discarded")).toBeInTheDocument();
    expect(screen.getByText("Step 2")).toBeInTheDocument();
    // formatDate output varies by locale/config — assert the year renders.
    expect(screen.getByText(/2026/)).toBeInTheDocument();
  });

  it("labels step 0 as Baseline", () => {
    render(<TrialStatusCard status="baseline" stepIndex={0} />);
    expect(screen.getAllByText("Baseline").length).toBeGreaterThanOrEqual(1);
  });

  it("renders a dash without a status and omits step/date when absent", () => {
    render(<TrialStatusCard />);
    expect(screen.getByText("-")).toBeInTheDocument();
    expect(screen.queryByText(/Step/)).not.toBeInTheDocument();
  });
});
