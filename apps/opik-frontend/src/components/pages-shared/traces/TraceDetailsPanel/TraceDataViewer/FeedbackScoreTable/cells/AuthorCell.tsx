import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";

const AuthorCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;

  const authors = row.author
    ? [row.author]
    : Object.keys(row.value_by_author ?? {});

  const authorsList = authors.join(", ");

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span className="truncate">{authorsList}</span>
    </CellWrapper>
  );
};

export default AuthorCell;
