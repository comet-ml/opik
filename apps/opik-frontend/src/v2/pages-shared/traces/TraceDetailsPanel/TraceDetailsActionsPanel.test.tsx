import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { TooltipProvider } from "@/ui/tooltip";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import TraceDetailsActionsPanel from "./TraceDetailsActionsPanel";

vi.mock("clipboard-copy", () => ({ default: vi.fn() }));

vi.mock("@tanstack/react-router", () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock("@/store/AppStore", () => ({
  default: vi.fn((selector) =>
    selector({
      activeWorkspaceName: "test-workspace",
      activeProjectId: "test-project-id",
    }),
  ),
  useActiveProjectId: () => "test-project-id",
}));

vi.mock("@/api/traces/useTraceDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => true,
}));

const TRACE_ID = "019ce65c-e695-7f93-b75b-68a4a9141f09";
const SPAN_ID = "019ce65c-ec62-7753-acf0-4935ce3333da";

const renderPanel = (props = {}) =>
  render(
    <PermissionsProvider value={DEFAULT_PERMISSIONS}>
      <TooltipProvider>
        <TraceDetailsActionsPanel
          projectId="test-project-id"
          traceId={TRACE_ID}
          spanId={SPAN_ID}
          onDelete={vi.fn()}
          onClose={vi.fn()}
          treeData={[]}
          setActiveSection={vi.fn()}
          {...props}
        />
      </TooltipProvider>
    </PermissionsProvider>,
  );

const openActionsMenu = () => {
  // Radix opens dropdowns on pointerdown, not click.
  fireEvent.pointerDown(
    screen.getByText("Actions menu").closest("button")!,
    new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
  );
};

describe("TraceDetailsActionsPanel header", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders the trace name as the title", () => {
    renderPanel({ traceName: "evaluation_task" });

    expect(screen.getByText("evaluation_task")).toBeTruthy();
    expect(screen.queryByText(/^Trace: /)).toBeNull();
  });

  it("falls back to Trace when the name is missing", () => {
    renderPanel();

    expect(screen.getByText("Trace")).toBeTruthy();
  });

  it("renders the trace copy actions in the header", () => {
    renderPanel();

    expect(screen.getByLabelText("Copy trace ID")).toBeTruthy();
    expect(screen.getByLabelText("Copy trace link")).toBeTruthy();
  });

  it("no longer offers copy or share entries in the actions menu", () => {
    renderPanel();
    openActionsMenu();

    expect(screen.queryByText("Share")).toBeNull();
    expect(screen.queryByText("Copy trace ID")).toBeNull();
    expect(screen.queryByText("Copy span ID")).toBeNull();
  });

  it("keeps export and delete entries in the actions menu", () => {
    renderPanel();
    openActionsMenu();

    expect(screen.getByText("Export as CSV")).toBeTruthy();
    expect(screen.getByText("Export as JSON")).toBeTruthy();
    expect(screen.getByText("Delete trace")).toBeTruthy();
  });

  it("exposes exactly three actions in the menu", () => {
    renderPanel();
    openActionsMenu();

    expect(screen.getAllByRole("menuitem")).toHaveLength(3);
  });
});
