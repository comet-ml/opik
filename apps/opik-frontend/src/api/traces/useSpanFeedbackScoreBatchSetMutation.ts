import { useMutation, useQueryClient } from "@tanstack/react-query";
import chunk from "lodash/chunk";
import { AxiosError } from "axios";

import api, {
  COMPARE_EXPERIMENTS_KEY,
  SPANS_KEY,
  SPANS_REST_ENDPOINT,
  TRACE_KEY,
  TRACES_KEY,
} from "@/api/api";
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
      // Span scores also roll up onto traces and experiment views — same keys as
      // useTraceFeedbackScoreSetMutation's span branch + its always-on set.
      await queryClient.invalidateQueries({ queryKey: [SPANS_KEY] });
      await queryClient.invalidateQueries({ queryKey: ["spans-columns"] });
      await queryClient.invalidateQueries({ queryKey: ["spans-statistic"] });
      await queryClient.invalidateQueries({ queryKey: [TRACE_KEY] });
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
    },
  });
};

export default useSpanFeedbackScoreBatchSetMutation;
