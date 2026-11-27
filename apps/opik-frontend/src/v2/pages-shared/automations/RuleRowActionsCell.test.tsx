import { describe, expect, it, vi, beforeEach } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { CellContext } from "@tanstack/react-table";

import RuleRowActionsCell from "@/v2/pages-shared/automations/RuleRowActionsCell";
import { EvaluatorsRule } from "@/types/automations";
import { TooltipProvider } from "@/ui/tooltip";

const openSidebar = vi.fn();

vi.mock("@tanstack/react-router", () => ({
  useRouter: () => null,
  Link: ({
    to,
    search,
    target,
    children,
    ...props
  }: {
    to: string;
    search?: Record<string, string>;
    target?: string;
    children: React.ReactNode;
  }) => (
    <a
      href={to}
      data-search={JSON.stringify(search)}
      target={target}
      {...props}
    >
      {children}
    </a>
  ),
}));
vi.mock("@/store/AppStore", () => ({
  default: (selector: (state: { activeWorkspaceName: string }) => unknown) =>
    selector({ activeWorkspaceName: "my-workspace" }),
}));
vi.mock("@/api/automations/useRulesBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock(
  "@/v2/pages-shared/traces/TraceLogsSidebar/useTraceLogsSidebarControls",
  () => ({ useTraceLogsSidebarControls: () => ({ openSidebar }) }),
);

const renderCell = ({
  rule,
  canEdit = true,
}: {
  rule: Partial<EvaluatorsRule>;
  canEdit?: boolean;
}) =>
  render(
    <TooltipProvider>
      <RuleRowActionsCell
        {...({
          row: { original: { id: "rule-1", name: "My rule", ...rule } },
        } as unknown as CellContext<EvaluatorsRule, unknown>)}
        canEdit={canEdit}
        openEditDialog={vi.fn()}
        openCloneDialog={vi.fn()}
      />
    </TooltipProvider>,
  );

const openActions = () =>
  fireEvent.click(screen.getByRole("button", { name: "More actions" }));

describe("RuleRowActionsCell", () => {
  beforeEach(() => openSidebar.mockClear());

  it("opens the rule's traces in the locked sidebar", () => {
    renderCell({ rule: { project_id: "project-1" } });
    openActions();

    fireEvent.click(screen.getByRole("button", { name: "Traces" }));

    expect(openSidebar).toHaveBeenCalledWith(
      [
        expect.objectContaining({
          field: "metadata",
          key: "rule_id",
          value: "rule-1",
        }),
      ],
      { locked: true, label: "My rule" },
    );
  });

  it("hides Traces when the rule has no project", () => {
    renderCell({ rule: {} });
    openActions();

    expect(
      screen.queryByRole("button", { name: "Traces" }),
    ).not.toBeInTheDocument();
  });

  it("links to the rule's execution logs in a new tab", () => {
    renderCell({ rule: {} });
    openActions();

    const logs = screen.getByRole("link", { name: "Execution logs" });
    expect(logs).toHaveAttribute("href", "/$workspaceName/automation-logs");
    expect(logs).toHaveAttribute("target", "_blank");
    expect(logs).toHaveAttribute(
      "data-search",
      JSON.stringify({ rule_id: "rule-1" }),
    );
  });

  it("offers Edit, Clone and Delete only to editors", () => {
    renderCell({ rule: {}, canEdit: false });

    // Only Execution logs is left, so it is shown directly.
    expect(
      screen.queryByRole("button", { name: "More actions" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Execution logs" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Edit" }),
    ).not.toBeInTheDocument();
  });
});
