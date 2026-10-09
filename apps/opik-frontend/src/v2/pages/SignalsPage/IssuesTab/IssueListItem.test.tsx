import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render as renderComponent, screen } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";
import IssueListItem from "./IssueListItem";

const render = (ui: React.ReactElement) =>
  renderComponent(<TooltipProvider>{ui}</TooltipProvider>);

const issue: AgentInsightsIssue = {
  id: "i1",
  name: "Agent tool loop",
  status: AGENT_INSIGHTS_ISSUE_STATUS.closed,
  total_occurrences: 10,
  latest_count: 2,
  total: 100,
  users_impacted: 3,
  total_users: 10,
  days_reported: 1,
  last_seen: "2026-10-05T10:00:00",
  status_changed_by: "Olesya",
  status_changed_at: "2026-10-06T14:37:00",
};

describe("IssueListItem", () => {
  it("shows who closed an issue and when", () => {
    render(<IssueListItem issue={issue} isActive={false} onClick={vi.fn()} />);

    expect(screen.getByText("Closed Oct 6 by Olesya")).toBeInTheDocument();
    expect(screen.queryByText(/Last seen/)).not.toBeInTheDocument();
  });

  it("says Resolved for resolved issues", () => {
    render(
      <IssueListItem
        issue={{ ...issue, status: AGENT_INSIGHTS_ISSUE_STATUS.resolved }}
        isActive={false}
        onClick={vi.fn()}
      />,
    );

    expect(screen.getByText("Resolved Oct 6 by Olesya")).toBeInTheDocument();
  });

  it("falls back to Last seen when the close wasn't recorded", () => {
    render(
      <IssueListItem
        issue={{
          ...issue,
          status_changed_by: undefined,
          status_changed_at: undefined,
        }}
        isActive={false}
        onClick={vi.fn()}
      />,
    );

    expect(screen.getByText(/Last seen: 5 Oct/)).toBeInTheDocument();
  });

  it("shows Last seen on open issues", () => {
    render(
      <IssueListItem
        issue={{ ...issue, status: AGENT_INSIGHTS_ISSUE_STATUS.open }}
        isActive={false}
        onClick={vi.fn()}
      />,
    );

    expect(screen.getByText(/Last seen: 5 Oct/)).toBeInTheDocument();
    expect(screen.queryByText(/Closed/)).not.toBeInTheDocument();
  });
});
