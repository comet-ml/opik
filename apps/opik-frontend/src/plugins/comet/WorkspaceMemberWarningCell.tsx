import { CellContext } from "@tanstack/react-table";
import { AlertTriangle } from "lucide-react";
import { Tag } from "@/components/ui/tag";
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
        <Tag variant="yellow" size="md">
          <div className="flex items-center gap-1">
            <AlertTriangle className="size-3 shrink-0" />
            <span className="truncate">Permissions mismatch</span>
          </div>
        </Tag>
      </TooltipWrapper>
    </CellWrapper>
  );
};

export default WorkspaceMemberWarningCell;
