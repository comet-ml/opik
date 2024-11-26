import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { SPANS_REST_ENDPOINT, TRACES_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

type UseTraceFeedbackScoreSetMutationParams = {
  categoryName?: string;
  name: string;
  spanId?: string;
  traceId: string;
  value: number;
};

const useTraceFeedbackScoreSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      categoryName,
      name,
      spanId,
      traceId,
      value,
    }: UseTraceFeedbackScoreSetMutationParams) => {
      const endpoint = spanId
        ? `${SPANS_REST_ENDPOINT}${spanId}/feedback-scores`
        : `${TRACES_REST_ENDPOINT}${traceId}/feedback-scores`;

      const { data } = await api.put(endpoint, {
        category_name: categoryName,
        name,
        source: FEEDBACK_SCORE_TYPE.ui,
        value,
      });

      return data;
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
    onSettled: async (data, error, variables) => {
      if (variables.spanId) {
        await queryClient.invalidateQueries({ queryKey: ["spans"] });
        await queryClient.invalidateQueries({ queryKey: ["spans-columns"] });
      }

      await queryClient.invalidateQueries({ queryKey: ["traces"] });
      await queryClient.invalidateQueries({ queryKey: ["traces-columns"] });

      await queryClient.invalidateQueries({
        queryKey: ["trace", { traceId: variables.traceId }],
      });
      await queryClient.invalidateQueries({
        queryKey: ["experiments-columns"],
      });
      await queryClient.invalidateQueries({
        queryKey: ["compare-experiments"],
      });
    },
  });
};

export default useTraceFeedbackScoreSetMutation;
