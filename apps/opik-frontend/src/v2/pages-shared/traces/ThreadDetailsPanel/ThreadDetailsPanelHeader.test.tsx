import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { QueryParamProvider } from "use-query-params";
import { WindowHistoryAdapter } from "use-query-params/adapters/window";
import { TooltipProvider } from "@/ui/tooltip";
import { PermissionsProvider } from "@/contexts/PermissionsContext";
import { DEFAULT_PERMISSIONS } from "@/types/permissions";
import ThreadDetailsPanel from "./ThreadDetailsPanel";

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

vi.mock("@/api/traces/useThreadById", () => ({
  default: () => ({ data: undefined, isPending: false }),
}));

vi.mock("@/api/traces/useTracesList", () => ({
  default: () => ({ data: undefined, isPending: false }),
}));

vi.mock("@/api/traces/useThreadBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/api/traces/useThreadFeedbackScoreDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));

vi.mock("@/ui/use-toast", () => ({
  useToast: () => ({ toast: vi.fn() }),
  toast: vi.fn(),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => true,
}));

vi.mock("@/hooks/useThreadMedia", () => ({
  useThreadMedia: () => ({ media: {} }),
}));

const THREAD_ID = "e5ff6303-d9af-45da-ab83-2f862e478ef0";

const renderPanel = (props = {}) => {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      <QueryParamProvider adapter={WindowHistoryAdapter}>
        <PermissionsProvider value={DEFAULT_PERMISSIONS}>
          <TooltipProvider>
            <ThreadDetailsPanel
              projectId="test-project-id"
              projectName="test-project"
              threadId={THREAD_ID}
              setTraceId={vi.fn()}
              open
              onClose={vi.fn()}
              {...props}
            />
          </TooltipProvider>
        </PermissionsProvider>
      </QueryParamProvider>
    </QueryClientProvider>,
  );
};

const openActionsMenu = () => {
  // Radix opens dropdowns on pointerdown, not click.
  fireEvent.pointerDown(
    screen.getByText("Actions menu").closest("button")!,
    new PointerEvent("pointerdown", { bubbles: true, button: 0 }),
  );
};

describe("ThreadDetailsPanel header", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("renders a Thread title without the id", () => {
    renderPanel();

    expect(screen.getByText("Thread")).toBeTruthy();
    expect(screen.queryByText(new RegExp(THREAD_ID))).toBeNull();
  });

  it("renders the thread copy actions in the header", () => {
    renderPanel();

    expect(screen.getByLabelText("Copy thread ID")).toBeTruthy();
    expect(screen.getByLabelText("Copy thread link")).toBeTruthy();
  });

  it("no longer offers copy or share entries in the actions menu", () => {
    renderPanel();
    openActionsMenu();

    expect(screen.queryByText("Share")).toBeNull();
    expect(screen.queryByText("Copy thread ID")).toBeNull();
  });

  it("keeps export and delete entries in the actions menu", () => {
    renderPanel();
    openActionsMenu();

    expect(screen.getByText("Export as CSV")).toBeTruthy();
    expect(screen.getByText("Export as JSON")).toBeTruthy();
    expect(screen.getByText("Delete")).toBeTruthy();
  });

  it("exposes exactly three actions in the menu", () => {
    renderPanel();
    openActionsMenu();

    expect(screen.getAllByRole("menuitem")).toHaveLength(3);
  });
});
