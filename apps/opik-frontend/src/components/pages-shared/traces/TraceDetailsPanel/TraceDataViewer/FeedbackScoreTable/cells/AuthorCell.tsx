import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { getIsParentFeedbackScoreRow } from "../utils";
import { cn } from "@/lib/utils";

const AuthorCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;
  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(row);
  const authors = row.author
    ? [row.author]
    : Object.keys(row.value_by_author ?? {});

  const authorsList = authors.sort().join(", ");

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <span
        className={cn("truncate", {
          "text-light-slate": isParentFeedbackScoreRow,
        })}
      >
        {authorsList}
      </span>
    </CellWrapper>
  );
};

export default AuthorCell;
