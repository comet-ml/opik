import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import TracesActionsPanel from "./TracesActionsPanel";
import { TRACE_DATA_TYPE } from "@/hooks/useTracesOrSpansList";
import { Trace } from "@/types/traces";

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
      type,
    }: {
      open: boolean;
      rows: Trace[];
      type: TRACE_DATA_TYPE;
    }) =>
      open ? (
        <div role="dialog">
          {type}: {rows.length} selected
        </div>
      ) : null,
  }),
);

const renderPanel = (
  selectedRows: Trace[] = [{ id: "trace-1" }] as Trace[],
  type = TRACE_DATA_TYPE.traces,
) =>
  render(
    <TracesActionsPanel
      selectedRows={selectedRows}
      type={type}
      getDataForExport={vi.fn()}
      columnsToExport={[]}
      projectName="Project"
      projectId="project"
    />,
  );

describe("TracesActionsPanel bulk annotation", () => {
  beforeEach(() => {
    permissions.canLogTraceSpanThread = true;
    permissions.canAnnotateTraceSpanThread = true;
  });

  it("hides annotation without permission", () => {
    permissions.canAnnotateTraceSpanThread = false;
    renderPanel();
    expect(
      screen.queryByRole("button", { name: "Annotate" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Manage tags" }),
    ).toBeInTheDocument();
  });

  it("disables annotation with no selection", () => {
    renderPanel([]);
    expect(screen.getByRole("button", { name: "Annotate" })).toBeDisabled();
  });

  it.each([TRACE_DATA_TYPE.traces, TRACE_DATA_TYPE.spans])(
    "opens annotation for selected %s",
    (type) => {
      renderPanel([{ id: "selected" }] as Trace[], type);
      fireEvent.click(screen.getByRole("button", { name: "Annotate" }));
      expect(screen.getByRole("dialog")).toHaveTextContent(
        `${type}: 1 selected`,
      );
    },
  );
});
