import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import api, { TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

type FeedbackScoreItem = {
  id: string;
  name: string;
  value: number;
  reason?: string;
  source: FEEDBACK_SCORE_TYPE;
};

type UseTraceBatchFeedbackScoreSetMutationParams = {
  scores: FeedbackScoreItem[];
};

const useTraceBatchFeedbackScoreSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      scores,
    }: UseTraceBatchFeedbackScoreSetMutationParams) => {
      const { data } = await api.put(
        TRACES_REST_ENDPOINT + "feedback-scores",
        { scores }
      );
      return data;
    },
    onError: (error: AxiosError) => {
      const message =
        (error.response?.data as { message?: string })?.message ||
        error.message;
      toast({
        title: "Error",
        description: message,
        variant: "destructive",
      });
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
      queryClient.invalidateQueries({ queryKey: ["traces-columns"] });
      queryClient.invalidateQueries({ queryKey: ["traces-statistic"] });
    },
  });
};

export default useTraceBatchFeedbackScoreSetMutation;
