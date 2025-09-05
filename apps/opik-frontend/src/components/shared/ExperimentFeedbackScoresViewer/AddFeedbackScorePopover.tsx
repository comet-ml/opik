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
import { UpdateFeedbackScoreData } from "@/components/pages-shared/traces/TraceDetailsPanel/TraceAnnotateViewer/types";
import useTraceFeedbackScoreSetMutation from "@/api/traces/useTraceFeedbackScoreSetMutation";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";

type AddFeedbackScorePopoverProps = {
  feedbackScores: TraceFeedbackScore[];
  traceId: string;
  spanId?: string;
};

const AddFeedbackScorePopover: React.FunctionComponent<
  AddFeedbackScorePopoverProps
> = ({ feedbackScores = [], traceId, spanId }) => {
  const { mutate: setTraceFeedbackScore } = useTraceFeedbackScoreSetMutation();
  const { mutate: feedbackScoreDelete } = useTraceFeedbackScoreDeleteMutation();

  const onUpdateFeedbackScore = (data: UpdateFeedbackScoreData) => {
    setTraceFeedbackScore({
      ...data,
      traceId,
      spanId,
    });
  };

  const onDeleteFeedbackScore = (name: string) => {
    feedbackScoreDelete({ name, traceId, spanId });
  };

  return (
    <Popover>
      <PopoverTrigger asChild>
        <Button variant="outline" size="2xs">
          <Plus className="mr-1.5 size-3.5 shrink-0" /> Add score
        </Button>
      </PopoverTrigger>
      <PopoverContent side="top" align="end" className="p-0">
        <div className="max-h-[70vh] max-w-[400px] overflow-auto px-0 py-4">
          <FeedbackScoresEditor
            key={`${spanId}-${traceId}`}
            feedbackScores={feedbackScores}
            onUpdateFeedbackScore={onUpdateFeedbackScore}
            onDeleteFeedbackScore={onDeleteFeedbackScore}
            className="mt-4"
            entityCopy="traces"
          />
        </div>
      </PopoverContent>
    </Popover>
  );
};

export default AddFeedbackScorePopover;
