import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import ColoredTagNew from "../ColoredTag/ColoredTagNew";

const FeedbackScoreNameCell = (context: CellContext<unknown, string>) => {
  const value = context.getValue();

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5"
    >
      <ColoredTagNew label={value} className="px-0" />
    </CellWrapper>
  );
};

export default FeedbackScoreNameCell;
