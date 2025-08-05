import React, { useCallback } from "react";
import { Trace, TraceFeedbackScore } from "@/types/traces";
import { UpdateFeedbackScoreData } from "./types";
import MultiScoringFeedbackScoresEditor from "../../FeedbackScoresEditor/MultiScoringFeedbackScoresEditor";
import useMultiScoring from "@/hooks/useMultiScoring";
import { Skeleton } from "@/components/ui/skeleton";

type TraceAnnotateViewerWithMultiScoringProps = {
  trace: Trace;
  onUpdateFeedbackScore: (update: UpdateFeedbackScoreData) => void;
  onDeleteFeedbackScore: (name: string) => void;
};

const TraceAnnotateViewerWithMultiScoring: React.FunctionComponent<TraceAnnotateViewerWithMultiScoringProps> = ({
  trace,
  onUpdateFeedbackScore,
  onDeleteFeedbackScore,
}) => {
  const {
    feedbackScoreGroups,
    isLoading: isLoadingMultiScoring,
    error: multiScoringError,
    handleDeleteScore,
    handleAddScore,
    isDeleting,
  } = useMultiScoring({
    entityId: trace.id,
    entityType: "trace",
  });

  const handleDeleteScoreById = useCallback(
    (scoreId: string) => {
      handleDeleteScore(scoreId);
    },
    [handleDeleteScore]
  );

  const handleAddScoreForMetric = useCallback(
    (name: string) => {
      handleAddScore(name);
      // In a real implementation, this would open a scoring dialog
      // For now, we'll use the existing single-scoring flow
      console.log(`Adding score for metric: ${name}`);
    },
    [handleAddScore]
  );

  if (isLoadingMultiScoring) {
    return (
      <div className="space-y-4">
        <Skeleton className="h-4 w-32" />
        <Skeleton className="h-20 w-full" />
        <Skeleton className="h-20 w-full" />
      </div>
    );
  }

  if (multiScoringError) {
    return (
      <div className="text-center py-8 text-muted-foreground">
        <p>Failed to load multi-scoring data</p>
        <p className="text-sm">Please try refreshing the page</p>
      </div>
    );
  }

  return (
    <MultiScoringFeedbackScoresEditor
      feedbackScores={trace.feedback_scores}
      feedbackScoreGroups={feedbackScoreGroups}
      onUpdateFeedbackScore={onUpdateFeedbackScore}
      onDeleteFeedbackScore={onDeleteFeedbackScore}
      onDeleteScoreById={handleDeleteScoreById}
      onAddScore={handleAddScoreForMetric}
      entityCopy="trace"
      entityId={trace.id}
      entityType="trace"
      isLoading={isLoadingMultiScoring || isDeleting}
    />
  );
};

export default TraceAnnotateViewerWithMultiScoring;