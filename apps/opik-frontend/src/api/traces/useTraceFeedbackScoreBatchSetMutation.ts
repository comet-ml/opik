import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { useToast } from "@/ui/use-toast";
import { extractErrorMessage } from "@/lib/errors";

type FeedbackScoreEntry = {
  id: string;
  name: string;
  value: number;
  categoryName?: string;
  reason?: string;
};

type UseTraceFeedbackScoreBatchSetMutationParams = {
  projectId: string;
  scores: FeedbackScoreEntry[];
};

const useTraceFeedbackScoreBatchSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      scores,
    }: UseTraceFeedbackScoreBatchSetMutationParams) => {
      const { data } = await api.put(`${TRACES_REST_ENDPOINT}feedback-scores`, {
        scores: scores.map((score) => ({
          id: score.id,
          name: score.name,
          value: score.value,
          source: FEEDBACK_SCORE_TYPE.ui,
          category_name: score.categoryName,
          reason: score.reason,
        })),
      });

      return data;
    },
    onError: (error: AxiosError) => {
      toast({
        title: "Error",
        description: extractErrorMessage(error),
        variant: "destructive",
      });
    },
    onSettled: (data, error, variables) => {
      queryClient.invalidateQueries({
        queryKey: [TRACES_KEY, { projectId: variables.projectId }],
      });
    },
  });
};

export default useTraceFeedbackScoreBatchSetMutation;
