import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  render as renderComponent,
  screen,
  fireEvent,
  within,
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
  cause: "The planner has no fast path.",
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

  const renderWithToggle = () =>
    render(
      <IssueDetail
        issue={baseIssue}
        projectId="p1"
        canConfigure
        canCloseAsNotUseful
      />,
    );

  const dialogCloseButton = () =>
    within(screen.getByRole("dialog")).getByRole("button", {
      name: "Close issue",
    });

  it("closes as resolved from the dialog, Resolved preselected", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));

    expect(screen.getByRole("radio", { name: /Resolved/ })).toBeChecked();
    expect(screen.getByRole("radio", { name: /Resolved/ })).toHaveFocus();
    expect(
      screen.getByText(
        "The issue will move to Closed issues. You can reopen it at any time.",
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByLabelText("Why isn't it useful? (Optional)"),
    ).not.toBeInTheDocument();

    fireEvent.click(dialogCloseButton());

    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "resolved" },
      expect.anything(),
    );
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Issue was resolved" }),
    );
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();

    clickUndo();

    expect(mutate).toHaveBeenLastCalledWith({
      issueId: "i1",
      projectId: "p1",
      status: "open",
      closeNote: undefined,
    });
  });

  it("closes as not useful with the reason, and Undo reopens it", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.click(screen.getByRole("radio", { name: /Not useful/ }));
    fireEvent.change(screen.getByLabelText("Why isn't it useful? (Optional)"), {
      target: { value: "  Retries are by design  " },
    });
    fireEvent.click(dialogCloseButton());

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

  it("keeps the dialog and the reason when closing fails", () => {
    mutate.mockImplementation(() => undefined);
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.click(screen.getByRole("radio", { name: /Not useful/ }));
    fireEvent.change(screen.getByLabelText("Why isn't it useful? (Optional)"), {
      target: { value: "Expected" },
    });
    fireEvent.click(dialogCloseButton());

    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(
      screen.getByLabelText("Why isn't it useful? (Optional)"),
    ).toHaveValue("Expected");
    expect(toast).not.toHaveBeenCalled();
  });

  it("submits with Enter; in the reason field Enter is a new line and Cmd+Enter submits", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.click(screen.getByRole("radio", { name: /Not useful/ }));
    const reason = screen.getByLabelText("Why isn't it useful? (Optional)");
    fireEvent.change(reason, { target: { value: "Expected" } });

    fireEvent.keyDown(reason, { key: "Enter" });
    expect(mutate).not.toHaveBeenCalled();

    fireEvent.keyDown(reason, { key: "Enter", metaKey: true });
    expect(mutate).toHaveBeenCalledWith(
      {
        issueId: "i1",
        projectId: "p1",
        status: "closed",
        closeNote: "Expected",
      },
      expect.anything(),
    );
  });

  it("submits the preselected Resolved with Enter, but Enter on Cancel only cancels", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.keyDown(screen.getByRole("button", { name: "Cancel" }), {
      key: "Enter",
    });
    expect(mutate).not.toHaveBeenCalled();

    fireEvent.keyDown(screen.getByRole("radio", { name: /Resolved/ }), {
      key: "Enter",
    });
    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "resolved" },
      expect.anything(),
    );
  });

  it("starts again on Resolved after a cancelled Not useful choice", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.click(screen.getByRole("radio", { name: /Not useful/ }));
    fireEvent.change(screen.getByLabelText("Why isn't it useful? (Optional)"), {
      target: { value: "Draft" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));
    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));

    const resolved = screen.getByRole("radio", { name: /Resolved/ });
    expect(resolved).toBeChecked();
    expect(resolved).toHaveFocus();
    expect(
      screen.queryByLabelText("Why isn't it useful? (Optional)"),
    ).not.toBeInTheDocument();
  });

  it("selects an option from anywhere on its card", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.click(
      screen.getByRole("radio", { name: /Not useful/ }).parentElement!,
    );

    expect(screen.getByRole("radio", { name: /Not useful/ })).toBeChecked();
  });

  it("doesn't submit on Enter on the dialog's X", () => {
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.keyDown(screen.getByRole("button", { name: "Close" }), {
      key: "Enter",
    });

    expect(mutate).not.toHaveBeenCalled();
  });

  it("keeps the dialog when resolving fails", () => {
    mutate.mockImplementation(() => undefined);
    renderWithToggle();

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));
    fireEvent.click(dialogCloseButton());

    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "resolved" },
      expect.anything(),
    );
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    expect(toast).not.toHaveBeenCalled();
  });

  it("resolves in one click with a toast when the guidance toggle is off", () => {
    render(<IssueDetail issue={baseIssue} projectId="p1" canConfigure />);

    fireEvent.click(screen.getByRole("button", { name: "Close issue" }));

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "resolved" },
      expect.anything(),
    );
    expect(toast).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Issue was resolved" }),
    );
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
    expect(screen.queryByText("Ollie fix")).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Reopen" }));

    expect(mutate).toHaveBeenCalledWith(
      { issueId: "i1", projectId: "p1", status: "open" },
      expect.anything(),
    );
    const { title, description } = toast.mock.calls.at(-1)![0];
    expect(title).toBe("Issue was reopened");
    renderComponent(<>{description}</>);
    expect(
      screen.getByText(
        "The issue is open again and will no longer guide future runs",
      ),
    ).toBeInTheDocument();

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
