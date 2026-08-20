import { useMutation, useQueryClient } from "@tanstack/react-query";
import chunk from "lodash/chunk";
import { AxiosError } from "axios";

import api, {
  COMPARE_EXPERIMENTS_KEY,
  TRACE_KEY,
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { useToast } from "@/ui/use-toast";
import { extractErrorMessage } from "@/lib/errors";
import {
  FeedbackScoreBatchEntry,
  MAX_FEEDBACK_SCORES_PER_BATCH,
} from "@/lib/feedback-scores";

type UseTraceFeedbackScoreBatchSetMutationParams = {
  projectName: string;
  scores: FeedbackScoreBatchEntry[];
};

const useTraceFeedbackScoreBatchSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectName,
      scores,
    }: UseTraceFeedbackScoreBatchSetMutationParams) => {
      for (const scoresChunk of chunk(scores, MAX_FEEDBACK_SCORES_PER_BATCH)) {
        await api.put(`${TRACES_REST_ENDPOINT}feedback-scores`, {
          scores: scoresChunk.map((score) => ({
            id: score.id,
            // The backend groups the batch by project name and derives project_id from it,
            // falling back to the default project when it is blank, so it has to be sent.
            project_name: projectName,
            name: score.name,
            category_name: score.categoryName,
            value: score.value,
            reason: score.reason,
            source: FEEDBACK_SCORE_TYPE.ui,
          })),
        });
      }
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: extractErrorMessage(error),
        variant: "destructive",
      });
    },
    onSettled: async (data, error, variables) => {
      // Same invalidation set as useTraceFeedbackScoreSetMutation.
      await queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
      await queryClient.invalidateQueries({ queryKey: ["traces-columns"] });
      await queryClient.invalidateQueries({ queryKey: ["traces-statistic"] });
      await queryClient.invalidateQueries({
        queryKey: ["experiment-items-statistic"],
      });
      await queryClient.invalidateQueries({
        queryKey: ["experiments-columns"],
      });
      await queryClient.invalidateQueries({ queryKey: ["experiment"] });
      await queryClient.invalidateQueries({
        queryKey: [COMPARE_EXPERIMENTS_KEY],
      });

      const traceIds = [...new Set(variables.scores.map((score) => score.id))];
      await Promise.all(
        traceIds.map((traceId) =>
          queryClient.invalidateQueries({
            queryKey: [TRACE_KEY, { traceId }],
          }),
        ),
      );
    },
  });
};

export default useTraceFeedbackScoreBatchSetMutation;
