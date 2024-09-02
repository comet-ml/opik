import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, { SPANS_REST_ENDPOINT, TRACES_REST_ENDPOINT } from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";

type UseTraceFeedbackScoreDeleteMutationParams = {
  name: string;
  spanId?: string;
  traceId: string;
};

const useTraceFeedbackScoreDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      name,
      spanId,
      traceId,
    }: UseTraceFeedbackScoreDeleteMutationParams) => {
      const endpoint = spanId
        ? `${SPANS_REST_ENDPOINT}${spanId}/feedback-scores/delete`
        : `${TRACES_REST_ENDPOINT}${traceId}/feedback-scores/delete`;

      const { data } = await api.post(endpoint, { name });

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
      } else {
        await queryClient.invalidateQueries({ queryKey: ["traces"] });

        await queryClient.invalidateQueries({
          queryKey: ["trace", { traceId: variables.traceId }],
        });
      }
      await queryClient.invalidateQueries({
        queryKey: ["compare-experiments"],
      });
    },
  });
};

export default useTraceFeedbackScoreDeleteMutation;
