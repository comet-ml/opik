import React from "react";
import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";

import { formatNumericData } from "@/lib/utils";
import { TraceFeedbackScore } from "@/types/traces";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";

const FeedbackScoreTagCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      {isObject(feedbackScore) ? (
        <FeedbackScoreTag
          label={feedbackScore.name}
          value={formatNumericData(feedbackScore.value)}
          reason={feedbackScore.reason}
          className="overflow-hidden"
        />
      ) : (
        "-"
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreTagCell;
