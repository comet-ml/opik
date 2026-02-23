import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

export type FeedbackScoreBatchItem = {
  id: string;
  projectName?: string;
  name: string;
  value: number;
  categoryName?: string;
  reason?: string;
  source: FEEDBACK_SCORE_TYPE;
};

type UseTraceFeedbackScoreBatchMutationParams = {
  scores: FeedbackScoreBatchItem[];
};

const useTraceFeedbackScoreBatchMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      scores,
    }: UseTraceFeedbackScoreBatchMutationParams) => {
      const endpoint = `${TRACES_REST_ENDPOINT}feedback-scores`;

      const { data } = await api.put(endpoint, {
        scores: scores.map((score) => ({
          id: score.id,
          project_name: score.projectName,
          name: score.name,
          value: score.value,
          category_name: score.categoryName,
          reason: score.reason,
          source: score.source,
        })),
      });

      return data;
    },
    onSuccess: (_, variables) => {
      toast({
        title: "Scores applied",
        description: `Successfully applied feedback scores to ${variables.scores.length} trace(s)`,
      });
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
      await queryClient.invalidateQueries({ queryKey: ["traces-columns"] });
      await queryClient.invalidateQueries({ queryKey: ["traces-statistic"] });
    },
  });
};

export default useTraceFeedbackScoreBatchMutation;
