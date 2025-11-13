import React from "react";
import { CellContext } from "@tanstack/react-table";

import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

const FeedbackDefinitionsValueCell = (
  context: CellContext<FeedbackDefinition, string>,
) => {
  const feedbackDefinition = context.row.original;

  let items = null;

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
    items = Object.keys(feedbackDefinition.details.categories || [])
      .sort()
      .join(", ");
  } else if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.numerical) {
    items = (
      <p>
        Min: {feedbackDefinition.details.min}, Max:{" "}
        {feedbackDefinition.details.max}
      </p>
    );
  } else if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.boolean) {
    items = `${feedbackDefinition.details.true_label} / ${feedbackDefinition.details.false_label}`;
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <div className="flex max-h-full flex-row gap-2 overflow-x-auto">
        {items}
      </div>
    </CellWrapper>
  );
};

export default FeedbackDefinitionsValueCell;
