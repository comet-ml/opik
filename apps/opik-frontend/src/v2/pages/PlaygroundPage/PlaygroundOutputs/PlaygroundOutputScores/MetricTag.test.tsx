import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import MetricTag from "./MetricTag";
import {
  ONLINE_SCORING_REASON,
  ONLINE_SCORING_STATE,
  OnlineScoringStatus,
} from "@/types/automations";

vi.mock("@tanstack/react-router", () => ({
  Link: ({
    children,
    search,
    "aria-label": ariaLabel,
  }: {
    children: React.ReactNode;
    search: { rule_id: string };
    "aria-label": string;
  }) => (
    <a
      aria-label={ariaLabel}
      href={`/automation-logs?rule_id=${search.rule_id}`}
    >
      {children}
    </a>
  ),
}));

vi.mock("@/shared/ColorIndicator/ColorIndicator", () => ({
  default: () => null,
}));

vi.mock("@/shared/FeedbackScoreTag/FeedbackScoreTag", () => ({
  default: ({ label, value }: { label: string; value: number }) => (
    <span>{`${label}: ${value}`}</span>
  ),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) => selector({ activeWorkspaceName: "ws" })),
}));

vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({
    children,
    content,
  }: {
    children: React.ReactNode;
    content: string;
  }) => <span title={content}>{children}</span>,
}));

const renderTag = (props: Partial<React.ComponentProps<typeof MetricTag>>) =>
  render(<MetricTag metricName="Relevance" color="#000" {...props} />);

const isSpinning = (container: HTMLElement) =>
  !!container.querySelector(".animate-spin");

describe("MetricTag", () => {
  it("spins while the score may still arrive", () => {
    const { container } = renderTag({ ruleId: "r1" });

    expect(isSpinning(container)).toBe(true);
  });

  it("stops spinning once the wait is over and links to the rule's execution logs", () => {
    const { container } = renderTag({ ruleId: "r1", waitOver: true });

    expect(isSpinning(container)).toBe(false);
    expect(
      screen.getByLabelText("Open the rule's execution logs"),
    ).toHaveAttribute("href", "/automation-logs?rule_id=r1");
    expect(screen.getByTitle(/No score arrived/)).toHaveTextContent("—");
  });

  it("says no score arrived without a link when the metric's rule is unknown", () => {
    const { container } = renderTag({ waitOver: true });

    expect(isSpinning(container)).toBe(false);
    expect(screen.getByTitle("No score arrived.")).toHaveTextContent("—");
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("shows the score once it arrives, even after the wait", () => {
    renderTag({ score: { value: 1 }, ruleId: "r1", waitOver: true });

    expect(screen.getByText("Relevance: 1")).toBeInTheDocument();
    expect(screen.queryByTitle(/No score arrived/)).not.toBeInTheDocument();
  });

  describe("when the rule reports why there is no score", () => {
    const status = (
      state: ONLINE_SCORING_STATE,
      extra: Partial<OnlineScoringStatus> = {},
    ): OnlineScoringStatus => ({
      rule_id: "r2",
      suite_assertion: false,
      state,
      reason: ONLINE_SCORING_REASON.UNREACHABLE,
      updated_at: "",
      ...extra,
    });

    it("ignores a state this build doesn't know instead of failing to render", () => {
      renderTag({
        status: status("queued" as ONLINE_SCORING_STATE),
      });

      expect(screen.getByText("Relevance")).toBeInTheDocument();
      expect(screen.queryByLabelText(/^Scoring/)).not.toBeInTheDocument();
    });

    it.each([
      [ONLINE_SCORING_STATE.FAILED, "Scoring failed"],
      [ONLINE_SCORING_STATE.SKIPPED, "Scoring skipped"],
    ])("stops spinning when it %s and says why", (state, label) => {
      const { container } = renderTag({ ruleId: "r1", status: status(state) });

      expect(isSpinning(container)).toBe(false);
      expect(screen.getByLabelText(label)).toHaveAttribute(
        "href",
        "/automation-logs?rule_id=r2",
      );
      expect(
        screen.getByTitle(
          "The provider can't be reached. No score will be stored. Click to open the rule's execution logs.",
        ),
      ).toBeInTheDocument();
    });

    it("says which attempt failed while the backend retries, ahead of the end of the wait", () => {
      const { container } = renderTag({
        ruleId: "r1",
        waitOver: true,
        status: status(ONLINE_SCORING_STATE.RETRYING, {
          attempt: 1,
          max_attempts: 3,
        }),
      });

      expect(isSpinning(container)).toBe(false);
      expect(screen.getByLabelText("Scoring is retrying")).toBeInTheDocument();
      expect(
        screen.getByTitle(
          "The provider can't be reached. Retrying (attempt 1 of 3), which can take several minutes. Click to open the rule's execution logs.",
        ),
      ).toBeInTheDocument();
      expect(screen.queryByTitle(/No score arrived/)).not.toBeInTheDocument();
    });

    it("shows the score once it arrives, whatever the rule reported", () => {
      renderTag({
        score: { value: 1 },
        status: status(ONLINE_SCORING_STATE.RETRYING),
      });

      expect(screen.getByText("Relevance: 1")).toBeInTheDocument();
      expect(
        screen.queryByLabelText("Scoring is retrying"),
      ).not.toBeInTheDocument();
    });
  });
});
