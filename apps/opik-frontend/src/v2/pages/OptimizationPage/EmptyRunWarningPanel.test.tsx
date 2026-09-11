import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import EmptyRunWarningPanel from "./EmptyRunWarningPanel";
import { EMPTY_RUN_CAUSE } from "./optimizationOverviewHelpers";
import { Optimization } from "@/types/optimizations";

vi.mock("@/api/optimizations/useOptimizationStudioLogs", () => ({
  default: () => ({
    data: {
      content: "INFO run started\nINFO baseline scored 1.0",
      url: null,
      expiresAt: null,
    },
    dataUpdatedAt: 0,
  }),
}));

const optimization = {
  id: "opt-1",
  status: "completed",
} as unknown as Optimization;

describe("EmptyRunWarningPanel", () => {
  it("names the optimizer, not the metric, when no candidates were generated", () => {
    render(
      <EmptyRunWarningPanel
        optimization={optimization}
        cause={EMPTY_RUN_CAUSE.NO_CANDIDATES}
      />,
    );

    expect(screen.getByText("No candidates generated")).toBeInTheDocument();
    expect(screen.queryByText("No usable scores")).not.toBeInTheDocument();
    expect(
      screen.getByText(/produced no prompt variants to score/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/run it again/)).not.toBeInTheDocument();
  });

  it("keeps the scoring-failure copy and CTA when nothing scored", () => {
    render(
      <EmptyRunWarningPanel
        optimization={optimization}
        cause={EMPTY_RUN_CAUSE.SCORING_FAILED}
      />,
    );

    expect(screen.getByText("No usable scores")).toBeInTheDocument();
    expect(screen.getByText(/run it again/)).toBeInTheDocument();
  });

  it("shows exact scoring-health counts when the backend provided them", () => {
    render(
      <EmptyRunWarningPanel
        optimization={optimization}
        cause={EMPTY_RUN_CAUSE.SCORING_FAILED}
        scoringHealth={{ failed_count: 5, total_count: 5 }}
      />,
    );

    expect(screen.getByText(/All 5 items failed to score/)).toBeInTheDocument();
  });

  it("renders nothing when the backend says no item failed", () => {
    // The classifier and the item counts can diverge; the backend wins.
    const { container } = render(
      <EmptyRunWarningPanel
        optimization={optimization}
        cause={EMPTY_RUN_CAUSE.SCORING_FAILED}
        scoringHealth={{ failed_count: 0, total_count: 10 }}
      />,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it("offers the logs on both causes", () => {
    const { unmount } = render(
      <EmptyRunWarningPanel
        optimization={optimization}
        cause={EMPTY_RUN_CAUSE.NO_CANDIDATES}
      />,
    );
    expect(screen.getByText("View logs")).toBeInTheDocument();
    unmount();

    render(
      <EmptyRunWarningPanel
        optimization={optimization}
        cause={EMPTY_RUN_CAUSE.SCORING_FAILED}
      />,
    );
    expect(screen.getByText("View logs")).toBeInTheDocument();
  });
});
