import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

import MetricTag from "./MetricTag";

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
});
