import React from "react";
import { CellContext } from "@tanstack/react-table";
import { Tag } from "@/components/ui/tag";
import {
  FEEDBACK_DEFINITION_TYPE,
  FeedbackDefinition,
} from "@/types/feedback-definitions";

const FeedbackDefinitionsValueCell = (
  context: CellContext<FeedbackDefinition, string>,
) => {
  const feedbackDefinition = context.row.original;

  let items = null;

  if (feedbackDefinition.type === FEEDBACK_DEFINITION_TYPE.categorical) {
    items = Object.keys(feedbackDefinition.details.categories || [])
      .sort()
      .map((v) => (
        <Tag size="lg" variant="gray" className="shrink-0" key={v}>
          {v}
        </Tag>
      ));
  } else {
    items = (
      <>
        <div className="mx-1 flex shrink-0 items-center gap-1">
          Min
          <Tag size="lg" variant="gray">
            {feedbackDefinition.details.min}
          </Tag>
        </div>
        <div className="mx-1 flex shrink-0 items-center gap-1">
          Max
          <Tag size="lg" variant="gray">
            {feedbackDefinition.details.max}
          </Tag>
        </div>
      </>
    );
  }

  return (
    <div className="flex max-h-full flex-row gap-2 overflow-x-auto">
      {items}
    </div>
  );
};

export default FeedbackDefinitionsValueCell;
