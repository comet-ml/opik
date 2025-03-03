import React from "react";
import { TraceFeedbackScore } from "@/types/traces";
import FeedbackScoreTag from "@/components/shared/FeedbackScoreTag/FeedbackScoreTag";
import useTraceFeedbackScoreDeleteMutation from "@/api/traces/useTraceFeedbackScoreDeleteMutation";
import AddFeedbackScorePopover from "@/components/shared/FeedbackScoresEditor/AddFeedbackScorePopover";

type FeedbackScoresEditorProps = {
  feedbackScores: TraceFeedbackScore[];
  traceId: string;
  spanId?: string;
};

const FeedbackScoresEditor: React.FunctionComponent<
  FeedbackScoresEditorProps
> = ({ feedbackScores = [], traceId, spanId }) => {
  const feedbackScoreDeleteMutation = useTraceFeedbackScoreDeleteMutation();

  const handleDeleteFeedbackScore = (name: string) => {
    feedbackScoreDeleteMutation.mutate({
      traceId,
      spanId,
      name,
    });
  };

  return (
    <div className="flex w-full flex-wrap items-center gap-2 overflow-x-hidden">
      {feedbackScores.sort().map((feedbackScore) => {
        return (
          <FeedbackScoreTag
            key={feedbackScore.name + feedbackScore.value}
            label={feedbackScore.name}
            value={feedbackScore.value}
            reason={feedbackScore.reason}
            lastUpdatedAt={feedbackScore.last_updated_at}
            lastUpdatedBy={feedbackScore.last_updated_by}
            onDelete={handleDeleteFeedbackScore}
            className="max-w-full"
          />
        );
      })}
      <AddFeedbackScorePopover
        feedbackScores={feedbackScores}
        traceId={traceId}
        spanId={spanId}
      ></AddFeedbackScorePopover>
    </div>
  );
};

export default FeedbackScoresEditor;
