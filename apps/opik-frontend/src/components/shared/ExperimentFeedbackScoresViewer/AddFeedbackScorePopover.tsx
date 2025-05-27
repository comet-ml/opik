import React from "react";
import { Plus } from "lucide-react";

import { Button } from "@/components/ui/button";
import {
  Popover,
  PopoverContent,
  PopoverTrigger,
} from "@/components/ui/popover";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoresEditor from "@/components/pages-shared/traces/FeedbackScoresEditor/FeedbackScoresEditor";

type AddFeedbackScorePopoverProps = {
  feedbackScores: TraceFeedbackScore[];
  traceId: string;
  spanId?: string;
};

const AddFeedbackScorePopover: React.FunctionComponent<
  AddFeedbackScorePopoverProps
> = ({ feedbackScores = [], traceId, spanId }) => {
  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" size="2xs">
          <Plus className="mr-2 size-3.5 shrink-0" /> Add score
        </Button>
      </PopoverTrigger>
      <PopoverContent side="top" align="end" className="p-0">
        <div className="max-h-[70vh] max-w-[400px] overflow-auto px-0 py-4">
          <FeedbackScoresEditor
            feedbackScores={feedbackScores}
            traceId={traceId}
            spanId={spanId}
          />
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default AddFeedbackScorePopover;
