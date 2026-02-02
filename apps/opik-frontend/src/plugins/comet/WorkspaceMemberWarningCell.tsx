import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { WorkspaceMember } from "./types";

const WorkspaceMemberWarningCell = (
  context: CellContext<WorkspaceMember, string>,
) => {
  const row = context.row.original;
  const mismatch = row.permissionMismatch;

  if (!mismatch) {
    return null;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <TooltipWrapper content={mismatch.message}>
        <span>Permissions mismatch</span>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default WorkspaceMemberWarningCell;
