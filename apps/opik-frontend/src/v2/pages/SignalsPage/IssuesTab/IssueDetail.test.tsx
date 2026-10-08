import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render as renderComponent,
  screen,
  fireEvent,
} from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import {
  AGENT_INSIGHTS_ISSUE_STATUS,
  AgentInsightsIssue,
} from "@/types/signals";

const mutate = vi.fn();
const toast = vi.fn();

vi.mock("@/api/signals/useUpdateAgentInsightsIssueMutation", () => ({
  default: () => ({ mutate, isPending: false }),
}));

vi.mock("@/api/signals/useAgentInsightsIssue", () => ({
  default: () => ({ data: undefined }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast }),
}));

vi.mock("@/v2/pages/SignalsPage/IssuesTab/AffectedTracesSample", () => ({
  default: () => null,
}));

vi.mock("@/lib/analytics/tracking", () => ({
  OpikEvent: {},
  trackEvent: vi.fn(),
}));

import IssueDetail from "./IssueDetail";

const render = (ui: React.ReactElement) =>
  renderComponent(<TooltipProvider>{ui}</TooltipProvider>);

const baseIssue: AgentInsightsIssue = {
  id: "i1",
  name: "Agent tool loop",
  status: AGENT_INSIGHTS_ISSUE_STATUS.open,
  total_occurrences: 10,
  latest_count: 2,
  total: 100,
  users_impacted: 3,
  total_users: 10,
  days_reported: 5,
};

const closedIssue: AgentInsightsIssue = {
  ...baseIssue,
  status: AGENT_INSIGHTS_ISSUE_STATUS.closed,
  close_note: "This is expected behavior",
  status_changed_by: "Olesya",
  status_changed_at: "2026-10-06T14:37:00",
};

// Clicks the Undo action of the last toast.
const clickUndo = () => {
  const { actions } = toast.mock.calls.at(-1)![0];
  renderComponent(<>{actions}</>);
  fireEvent.click(screen.getByRole("button", { name: "Undo" }));
};

describe("IssueDetail status actions", () => {
  beforeEach(() => {
    mutate.mockReset();
    mutate.mockImplementation((_vars, options) => options?.onSuccess?.());
    toast.mockReset();
  });

  it("closes an open issue as resolved", () => {
    render(
      <IssueDetail
        issue={baseIssue}
        projectId="p1"
        canConfigure
        canCloseAsNotUseful
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));

    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "resolved" },
      expect.anything(),
    );
  });

  it("closes as not useful with the reason, and Undo reopens it", () => {
    render(
      <IssueDetail
        issue={baseIssue}
        projectId="p1"
        canConfigure
        canCloseAsNotUseful
      />,
    );

    fireEvent.keyDown(
      screen.getByRole("button", { name: "Show more options" }),
      { key: "Enter" },
    );
    fireEvent.click(
      screen.getByRole("menuitem", { name: "Close as Not useful" }),
    );
    fireEvent.change(screen.getByLabelText("Why isn't it useful? (Optional)"), {
      target: { value: "  Retries are by design  " },
    });
    fireEvent.click(
      screen.getByRole("button", { name: "Close issue as Not useful" }),
    );

    expect(mutate).toHaveBeenCalledWith(
      {
        issueId: "i1",
        projectId: "p1",
        status: "closed",
        closeNote: "Retries are by design",
      },
      expect.anything(),
    );
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Issue was closed as Not useful" }),
    );

    clickUndo();

    expect(mutate).toHaveBeenLastCalledWith({
      issueId: "i1",
      projectId: "p1",
      status: "open",
      closeNote: undefined,
    });
  });

  it("offers only Close issue when the guidance toggle is off", () => {
    render(<IssueDetail issue={baseIssue} projectId="p1" canConfigure />);

    expect(
      screen.getByRole("button", { name: "Close issue" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Show more options" }),
    ).not.toBeInTheDocument();
  });

  it("shows how a not-useful issue was closed, and Undo of Reopen restores the reason", () => {
    render(
      <IssueDetail
        issue={closedIssue}
        projectId="p1"
        canConfigure
        canCloseAsNotUseful
      />,
    );

    expect(
      screen.getByText(/^Not useful\. Diagnostics won’t raise/),
    ).toBeInTheDocument();
    expect(screen.getByText("Olesya, 6 Oct 2026, 14:37")).toBeInTheDocument();
    expect(screen.getByText("This is expected behavior")).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Reopen" }));

    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "open" },
      expect.anything(),
    );
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({
        title: "Issue was reopened",
        description:
          "The issue is open again and will no longer guide future runs",
      }),
    );

    clickUndo();

    expect(mutate).toHaveBeenLastCalledWith({
      issueId: "i1",
      projectId: "p1",
      status: "closed",
      closeNote: "This is expected behavior",
    });
  });

  it("has no status actions without configure permission", () => {
    render(
      <IssueDetail
        issue={baseIssue}
        projectId="p1"
        canConfigure={false}
        canCloseAsNotUseful
      />,
    );

    expect(
      screen.queryByRole("button", { name: "Close issue" }),
    ).not.toBeInTheDocument();
  });
});
