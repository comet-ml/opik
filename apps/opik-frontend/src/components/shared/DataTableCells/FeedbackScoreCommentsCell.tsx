import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const FeedbackScoreCommentsCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <span className="comet-body-s">{value}</span>
    </CellWrapper>
  );
};

export default FeedbackScoreCommentsCell;

