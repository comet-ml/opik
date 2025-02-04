import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "../FeedbackScoreTag/FeedbackScoreTag";

const FeedbackScoreListCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScoreList = context.getValue() as
    | TraceFeedbackScore[]
    | undefined;
  const isEmpty = !feedbackScoreList?.length;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1.5 overflow-hidden"
    >
      {isEmpty
        ? "-"
        : feedbackScoreList.map<React.ReactNode>((item) => (
            <FeedbackScoreTag
              key={item.name}
              label={item.name}
              value={item.value}
              reason={item.reason}
            />
          ))}
    </CellWrapper>
  );
};

export default FeedbackScoreListCell;
