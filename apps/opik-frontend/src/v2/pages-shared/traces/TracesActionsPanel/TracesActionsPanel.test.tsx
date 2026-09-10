import { ReactNode } from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { TooltipProvider } from "@/ui/tooltip";
import TracesActionsPanel from "./TracesActionsPanel";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Trace } from "@/types/traces";

vi.mock("@/api/traces/useTraceBatchDeleteMutation", () => ({
  default: vi.fn(() => ({ mutate: vi.fn() })),
}));

vi.mock("@/api/automations/useFilteredRulesList", () => ({
  default: vi.fn(() => ({ rules: [], isLoading: false })),
}));

vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: vi.fn(() => true),
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: vi.fn(() => ({
    permissions: {
      canDeleteTraces: true,
      canLogTraceSpanThread: true,
      canAnnotateTraceSpanThread: true,
    },
  })),
}));

vi.mock(
  "@/v2/pages-shared/traces/AnnotateTracesDialog/AnnotateTracesDialog",
  () => ({
    default: ({ open }: { open: boolean }) =>
      open ? <div data-testid="mock-annotate-dialog">Annotate Dialog</div> : null,
  }),
);

describe("TracesActionsPanel", () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  const renderPanel = (selectedRows: Trace[] = []) => {
    return render(
      <QueryClientProvider client={queryClient}>
        <TooltipProvider>
          <TracesActionsPanel
            type={TRACE_DATA_TYPE.traces}
            getDataForExport={async () => []}
            selectedRows={selectedRows}
            columnsToExport={[]}
            projectName="test-project"
            projectId="test-project-id"
          />
        </TooltipProvider>
      </QueryClientProvider>,
    );
  };

  it("renders the Annotate button", () => {
    renderPanel();
    expect(screen.getByTestId("traces-bulk-annotate-button")).toBeInTheDocument();
  });

  it("disables the Annotate button when no rows are selected", () => {
    renderPanel([]);
    expect(screen.getByTestId("traces-bulk-annotate-button")).toBeDisabled();
  });

  it("enables the Annotate button when rows are selected and opens dialog on click", () => {
    const rows = [{ id: "t-1" } as Trace];
    renderPanel(rows);

    const button = screen.getByTestId("traces-bulk-annotate-button");
    expect(button).toBeEnabled();

    expect(screen.queryByTestId("mock-annotate-dialog")).not.toBeInTheDocument();

    fireEvent.click(button);

    expect(screen.getByTestId("mock-annotate-dialog")).toBeInTheDocument();
  });
});
