import { useState } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import useRetainedRowSelection from "@/hooks/useRetainedRowSelection";
import TracesActionsPanel from "@/v2/pages-shared/traces/TracesActionsPanel/TracesActionsPanel";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Trace } from "@/types/traces";

/**
 * Focused page-level coverage for the TraceLogsView selection path:
 * DataTable-driven rowSelection → useRetainedRowSelection → TracesActionsPanel.
 * Avoids mounting the full TraceLogsView tree; reuses TracesActionsPanel mocks.
 */

const { permissions } = vi.hoisted(() => ({
  permissions: {
    canDeleteTraces: false,
    canLogTraceSpanThread: true,
    canAnnotateTraceSpanThread: true,
  },
}));

vi.mock("@/contexts/PermissionsContext", () => ({
  usePermissions: () => ({ permissions }),
}));
vi.mock("@/contexts/feature-toggles-provider", () => ({
  useIsFeatureEnabled: () => true,
}));
vi.mock("@/api/traces/useTraceBatchDeleteMutation", () => ({
  default: () => ({ mutate: vi.fn() }),
}));
vi.mock("@/api/automations/useFilteredRulesList", () => ({
  default: () => ({ rules: [], isLoading: false }),
}));
vi.mock("@/v2/pages-shared/traces/AddToDropdown/AddToDropdown", () => ({
  default: () => null,
}));
vi.mock("@/shared/ConfirmDialog/ConfirmDialog", () => ({
  default: () => null,
}));
vi.mock("@/shared/ExportToButton/ExportToButton", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/traces/AddTagDialog/AddTagDialog", () => ({
  default: () => null,
}));
vi.mock("@/v2/pages-shared/automations/EvaluateButton/EvaluateButton", () => ({
  default: () => null,
}));
vi.mock(
  "@/v2/pages-shared/automations/RunEvaluationDialog/RunEvaluationDialog",
  () => ({ default: () => null }),
);
vi.mock("@/shared/TooltipWrapper/TooltipWrapper", () => ({
  default: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock(
  "@/v2/pages-shared/traces/AnnotateTracesOrSpansDialog/AnnotateTracesOrSpansDialog",
  () => ({
    default: ({
      open,
      rows,
    }: {
      open: boolean;
      rows: Trace[];
    }) =>
      open ? (
        <div role="dialog" data-testid="annotate-dialog">
          {rows.map((row) => row.id).join(",")}
        </div>
      ) : null,
  }),
);

const PAGE_1: Trace[] = [
  { id: "trace-page1" } as Trace,
  { id: "trace-other" } as Trace,
];
const PAGE_2: Trace[] = [{ id: "trace-page2" } as Trace];

const TraceLogsSelectionHarness = ({
  initialRows = PAGE_1,
}: {
  initialRows?: Trace[];
}) => {
  const [rows, setRows] = useState(initialRows);
  const { setRowSelection, selectedRows, clearRowSelection } =
    useRetainedRowSelection<Trace>({
      rows,
      scope: {
        projectId: "project-1",
        logsSource: "project",
        visibilityMode: "default",
      },
    });

  return (
    <div>
      <button
        type="button"
        onClick={() => setRowSelection({ "trace-page1": true })}
      >
        Select page-1 row
      </button>
      <button type="button" onClick={() => setRows(PAGE_2)}>
        Go to page 2
      </button>
      <div data-testid="selected-ids">
        {selectedRows.map((row) => row.id).join(",")}
      </div>
      {selectedRows.length > 0 ? (
        <TracesActionsPanel
          selectedRows={selectedRows}
          type={TRACE_DATA_TYPE.traces}
          getDataForExport={vi.fn()}
          columnsToExport={[]}
          projectName="Project"
          projectId="project-1"
          onAfterDelete={clearRowSelection}
        />
      ) : null}
    </div>
  );
};

describe("TraceLogsView retained selection → TracesActionsPanel", () => {
  beforeEach(() => {
    permissions.canLogTraceSpanThread = true;
    permissions.canAnnotateTraceSpanThread = true;
  });

  it("keeps the retained row in TracesActionsPanel after pagination leaves it off-page", () => {
    render(<TraceLogsSelectionHarness />);

    fireEvent.click(screen.getByRole("button", { name: "Select page-1 row" }));
    expect(screen.getByTestId("selected-ids")).toHaveTextContent("trace-page1");
    expect(
      screen.getByRole("button", { name: "Annotate" }),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Go to page 2" }));

    expect(screen.getByTestId("selected-ids")).toHaveTextContent("trace-page1");
    fireEvent.click(screen.getByRole("button", { name: "Annotate" }));
    expect(screen.getByTestId("annotate-dialog")).toHaveTextContent(
      "trace-page1",
    );
  });
});
