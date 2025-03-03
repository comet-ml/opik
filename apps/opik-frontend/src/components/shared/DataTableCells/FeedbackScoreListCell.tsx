import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";

const FeedbackScoreListCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScoreList = context.getValue() as TraceFeedbackScore[];
  const isEmpty = !feedbackScoreList?.length;

  const sortedList = feedbackScoreList.sort((c1, c2) =>
    c1.name.localeCompare(c2.name),
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5 overflow-x-auto overflow-y-hidden py-1"
    >
      {isEmpty
        ? "-"
        : sortedList.map<React.ReactNode>((item) => (
            <FeedbackScoreTag
              key={item.name}
              label={item.name}
              value={item.value}
              reason={item.reason}
              lastUpdatedAt={item.last_updated_at}
              lastUpdatedBy={item.last_updated_by}
            />
          ))}
    </CellWrapper>
  );
};

export default FeedbackScoreListCell;
