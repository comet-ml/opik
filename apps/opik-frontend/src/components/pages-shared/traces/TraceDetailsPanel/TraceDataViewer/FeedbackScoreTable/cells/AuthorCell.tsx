import { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { ExpandingFeedbackScoreRow } from "../types";
import { extractAuthorName, getIsParentFeedbackScoreRow } from "../utils";
import { cn } from "@/lib/utils";

const AuthorCell = (
  context: CellContext<ExpandingFeedbackScoreRow, string>,
) => {
  const row = context.row.original;
  const isParentFeedbackScoreRow = getIsParentFeedbackScoreRow(row);

  // Extract authors - handle composite keys (author_spanId) from span feedback scores
  const authorsList = useMemo(() => {
    let authors: string[];
    if (row.author) {
      authors = [row.author];
    } else if (row.value_by_author) {
      // For span feedback scores, keys might be composite (author_spanId)
      // Extract unique author names by removing the _spanId suffix
      const authorSet = new Set<string>();
      Object.keys(row.value_by_author).forEach((key) => {
        authorSet.add(extractAuthorName(key));
      });
      authors = Array.from(authorSet);
    } else {
      authors = [];
    }

    return authors.sort().join(", ");
  }, [row.author, row.value_by_author]);

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
