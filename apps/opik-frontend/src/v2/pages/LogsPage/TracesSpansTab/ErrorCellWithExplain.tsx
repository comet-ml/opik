import { CellContext } from "@tanstack/react-table";
import ErrorCell from "@/shared/DataTableCells/ErrorCell";
import usePluginsStore from "@/store/PluginsStore";
import { BaseTraceData, BaseTraceDataErrorInfo } from "@/types/traces";
import { ExplainTarget } from "@/types/assistant-sidebar";

// `project_id` is present on every Trace/Span row at runtime (not on the
// BaseTraceData column type), so read it through a narrow cast.
type RowWithProject = BaseTraceData & { project_id?: string };

const ErrorCellWithExplain = (
  context: CellContext<BaseTraceData, BaseTraceDataErrorInfo | undefined>,
) => {
  const ExplainButton = usePluginsStore((s) => s.ExplainButton);
  const value = context.getValue();
  const row = context.row.original as RowWithProject;

  const target: ExplainTarget | null =
    value && row.project_id
      ? {
          kind: "trace.error",
          entityId: row.id,
          projectId: row.project_id,
          payload: {
            exception_type: value.exception_type,
            message: value.message,
            traceback: value.traceback,
          },
        }
      : null;

  return (
    <div className="group/cell relative flex size-full items-center">
      {ErrorCell(context)}
      {ExplainButton && target && (
        <div className="absolute right-2 top-1/2 -translate-y-1/2 opacity-0 transition-opacity group-hover/cell:opacity-100">
          <ExplainButton target={target} />
        </div>
      )}
    </div>
  );
};

export default ErrorCellWithExplain;
