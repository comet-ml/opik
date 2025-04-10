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
          <Plus /> Add score
        </Button>
      </PopoverTrigger>
      <PopoverContent
        className="max-w-[400px] px-0 py-4"
        side="top"
        align="end"
      >
        <FeedbackScoresEditor
          feedbackScores={feedbackScores}
          traceId={traceId}
          spanId={spanId}
        />
      </PopoverContent>
    </Popover>
  );
};

export default AddFeedbackScorePopover;
