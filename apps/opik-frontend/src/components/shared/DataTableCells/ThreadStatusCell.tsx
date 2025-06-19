import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ThreadStatusTag from "../ThreadStatusTag/ThreadStatusTag";
import { ThreadStatus } from "@/types/thread";
import CellTooltipWrapper from "./CellTooltipWrapper";

const statusTooltipMap = {
  [ThreadStatus.CLOSE]:
    "This session has ended. You can now add feedback, comments, and tags.",
  [ThreadStatus.OPEN]:
    "This session is still running. The user is still interacting with the thread. You can’t add feedback, comments, and tags until the session has ended.",
};
const ThreadStatusCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  const status = ThreadStatus.CLOSE;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <CellTooltipWrapper content={statusTooltipMap[status]}>
        <div className="flex size-full overflow-hidden py-0.5">
          <ThreadStatusTag status={status} />
        </div>
      </CellTooltipWrapper>
    </CellWrapper>
  );
};

export default ThreadStatusCell;
