import React from "react";
import { CellContext } from "@tanstack/react-table";
import isObject from "lodash/isObject";

import { TraceFeedbackScore } from "@/types/traces";
import {
  getCellTagSize,
  FEEDBACK_SCORE_TAG_SIZE_MAP,
} from "@/constants/shared";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { EMPTY_CELL_PLACEHOLDER } from "@/shared/DataTableCells/EmptyCellPlaceholder";
import FeedbackScoreTag from "@/shared/FeedbackScoreTag/FeedbackScoreTag";

const FeedbackScoreTagCell = (context: CellContext<unknown, unknown>) => {
  const feedbackScore = context.getValue() as TraceFeedbackScore | undefined;
  const tagSize = getCellTagSize(context, FEEDBACK_SCORE_TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-1"
    >
      {isObject(feedbackScore) ? (
        <FeedbackScoreTag
          label={feedbackScore.name}
          value={feedbackScore.value}
          reason={feedbackScore.reason}
          className="overflow-hidden"
          size={tagSize}
        />
      ) : (
        EMPTY_CELL_PLACEHOLDER
      )}
    </CellWrapper>
  );
};

export default FeedbackScoreTagCell;
