import { describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ListTree, Pencil, ScrollText, Trash } from "lucide-react";

import RowActions, { RowAction } from "@/shared/DataTable/RowActions";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import { TooltipProvider } from "@/ui/tooltip";

const router = {
  state: {
    matches: [{ routeId: "/workspaceGuard/$workspaceName/configuration" }],
    location: { search: { tab: "ai-provider" } },
  },
  routesById: {
    "/workspaceGuard/$workspaceName/configuration": {
      fullPath: "/$workspaceName/configuration",
    },
  },
};

vi.mock("@/lib/analytics/tracking", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/analytics/tracking")>()),
  trackEvent: vi.fn(),
}));

vi.mock("@tanstack/react-router", () => ({
  useRouter: () => router,
  Link: ({
    to,
    target,
    children,
    ...props
  }: {
    to: string;
    target?: string;
    children: React.ReactNode;
  }) => (
    <a href={to} target={target} {...props}>
      {children}
    </a>
  ),
}));

const renderActions = (actions: RowAction[]) =>
  render(
    <TooltipProvider>
      <RowActions actions={actions} />
    </TooltipProvider>,
  );

describe("RowActions", () => {
  it("renders nothing without actions", () => {
    const { container } = renderActions([]);
    expect(container).toBeEmptyDOMElement();
  });

  it("shows a single action directly, without the more button", () => {
    const onClick = vi.fn();
    renderActions([{ id: "delete", label: "Delete", icon: Trash, onClick }]);

    expect(
      screen.queryByRole("button", { name: "More actions" }),
    ).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it("reveals several actions from the more button and closes after one runs", () => {
    const onEdit = vi.fn();
    renderActions([
      { id: "edit", label: "Edit", icon: Pencil, onClick: onEdit },
      { id: "delete", label: "Delete", icon: Trash, onClick: vi.fn() },
    ]);

    expect(
      screen.queryByRole("button", { name: "Edit" }),
    ).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));

    expect(onEdit).toHaveBeenCalledTimes(1);
    expect(
      screen.queryByRole("button", { name: "Edit" }),
    ).not.toBeInTheDocument();
  });

  it("does not run a disabled action", () => {
    const onClick = vi.fn();
    renderActions([
      {
        id: "delete",
        label: "Delete",
        icon: Trash,
        onClick,
        disabled: true,
        disabledReason: "Not allowed",
      },
    ]);

    const button = screen.getByRole("button", { name: "Delete" });
    expect(button).toHaveAttribute("aria-disabled", "true");
    fireEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it("closes the expanded actions from its own more button", () => {
    renderActions([
      { id: "edit", label: "Edit", icon: Pencil, onClick: vi.fn() },
      { id: "delete", label: "Delete", icon: Trash, onClick: vi.fn() },
    ]);

    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("button", { name: "Hide actions" }));

    expect(
      screen.queryByRole("button", { name: "Edit" }),
    ).not.toBeInTheDocument();
  });

  it("renders labelled links, marking only those that open a new tab", () => {
    renderActions([
      {
        id: "traces",
        label: "Traces",
        icon: ListTree,
        showLabel: true,
        link: { to: "/traces" },
      },
      {
        id: "logs",
        label: "Execution logs",
        icon: ScrollText,
        showLabel: true,
        link: { to: "/logs", target: "_blank" },
      },
    ]);

    fireEvent.click(screen.getByRole("button", { name: "More actions" }));

    const traces = screen.getByRole("link", { name: "Traces" });
    const logs = screen.getByRole("link", { name: "Execution logs" });
    expect(traces).toHaveTextContent("Traces");
    expect(traces.querySelector(".lucide-arrow-up-right")).toBeNull();
    expect(logs).toHaveAttribute("target", "_blank");
    expect(logs.querySelector(".lucide-arrow-up-right")).not.toBeNull();
  });

  it("reports which action ran and on which page", () => {
    vi.mocked(trackEvent).mockClear();
    renderActions([
      { id: "edit", label: "Edit", icon: Pencil, onClick: vi.fn() },
      { id: "delete", label: "Delete", icon: Trash, onClick: vi.fn() },
    ]);

    fireEvent.click(screen.getByRole("button", { name: "More actions" }));
    fireEvent.click(screen.getByRole("button", { name: "Edit" }));

    expect(trackEvent).toHaveBeenCalledWith(OpikEvent.ROW_ACTION_CLICKED, {
      page: "/$workspaceName/configuration",
      tab: "ai-provider",
      action: "edit",
    });
  });

  it("does not report a disabled action", () => {
    vi.mocked(trackEvent).mockClear();
    renderActions([
      {
        id: "delete",
        label: "Delete",
        icon: Trash,
        onClick: vi.fn(),
        disabled: true,
      },
    ]);

    fireEvent.click(screen.getByRole("button", { name: "Delete" }));

    expect(trackEvent).not.toHaveBeenCalled();
  });
});
