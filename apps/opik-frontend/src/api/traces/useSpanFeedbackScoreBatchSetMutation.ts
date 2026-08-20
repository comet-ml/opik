import { useMutation, useQueryClient } from "@tanstack/react-query";
import chunk from "lodash/chunk";
import { AxiosError } from "axios";

import api, { SPANS_KEY, SPANS_REST_ENDPOINT, TRACE_KEY } from "@/api/api";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { useToast } from "@/ui/use-toast";
import { extractErrorMessage } from "@/lib/errors";
import {
  FeedbackScoreBatchEntry,
  MAX_FEEDBACK_SCORES_PER_BATCH,
} from "@/lib/feedback-scores";

type UseSpanFeedbackScoreBatchSetMutationParams = {
  projectName: string;
  scores: FeedbackScoreBatchEntry[];
};

const useSpanFeedbackScoreBatchSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectName,
      scores,
    }: UseSpanFeedbackScoreBatchSetMutationParams) => {
      for (const scoresChunk of chunk(scores, MAX_FEEDBACK_SCORES_PER_BATCH)) {
        await api.put(`${SPANS_REST_ENDPOINT}feedback-scores`, {
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
    onSettled: async () => {
      // Mirror the span branch of useTraceFeedbackScoreSetMutation: refresh the spans list,
      // its columns/statistics and the trace details panel, which shows the aggregated span
      // scores. The batch only carries span ids, so the trace cache is invalidated broadly.
      await queryClient.invalidateQueries({ queryKey: [SPANS_KEY] });
      await queryClient.invalidateQueries({ queryKey: ["spans-columns"] });
      await queryClient.invalidateQueries({ queryKey: ["spans-statistic"] });
      await queryClient.invalidateQueries({ queryKey: [TRACE_KEY] });
    },
  });
};

export default useSpanFeedbackScoreBatchSetMutation;
