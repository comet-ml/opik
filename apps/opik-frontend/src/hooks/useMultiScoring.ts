import { useCallback } from "react";
import useAppStore from "@/store/AppStore";
import useTraceFeedbackScoreGroups from "@/api/traces/useTraceFeedbackScoreGroups";
import useSpanFeedbackScoreGroups from "@/api/spans/useSpanFeedbackScoreGroups";
import useDeleteTraceFeedbackScore from "@/api/traces/useDeleteTraceFeedbackScore";
import useDeleteSpanFeedbackScore from "@/api/spans/useDeleteSpanFeedbackScore";
import { FeedbackScoreGroup } from "@/types/traces";

type UseMultiScoringParams = {
  entityId: string;
  entityType: "trace" | "span";
};

export default function useMultiScoring({ entityId, entityType }: UseMultiScoringParams) {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  // Fetch feedback score groups
  const traceScoreGroupsQuery = useTraceFeedbackScoreGroups(
    { traceId: entityId, workspaceName },
    { enabled: entityType === "trace" }
  );

  const spanScoreGroupsQuery = useSpanFeedbackScoreGroups(
    { spanId: entityId, workspaceName },
    { enabled: entityType === "span" }
  );

  // Delete score mutations
  const deleteTraceScoreMutation = useDeleteTraceFeedbackScore();
  const deleteSpanScoreMutation = useDeleteSpanFeedbackScore();

  // Get the appropriate query and mutation based on entity type
  const scoreGroupsQuery = entityType === "trace" ? traceScoreGroupsQuery : spanScoreGroupsQuery;
  const deleteScoreMutation = entityType === "trace" ? deleteTraceScoreMutation : deleteSpanScoreMutation;

  const feedbackScoreGroups: FeedbackScoreGroup[] = scoreGroupsQuery.data || [];
  const isLoading = scoreGroupsQuery.isLoading;
  const error = scoreGroupsQuery.error;

  const handleDeleteScore = useCallback(
    (scoreId: string) => {
      deleteScoreMutation.mutate({
        [entityType === "trace" ? "traceId" : "spanId"]: entityId,
        scoreId,
        workspaceName,
      });
    },
    [deleteScoreMutation, entityId, entityType, workspaceName]
  );

  const handleAddScore = useCallback(
    (name: string) => {
      // This would typically open a dialog or navigate to a scoring form
      // For now, we'll just log it - the actual implementation would depend on the UI flow
      console.log(`Add score for ${name} on ${entityType} ${entityId}`);
    },
    [entityId, entityType]
  );

  return {
    feedbackScoreGroups,
    isLoading,
    error,
    handleDeleteScore,
    handleAddScore,
    isDeleting: deleteScoreMutation.isPending,
  };
}