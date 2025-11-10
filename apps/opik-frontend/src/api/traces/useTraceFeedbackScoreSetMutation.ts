import { useMutation, useQueryClient } from "@tanstack/react-query";
import get from "lodash/get";
import api, {
  COMPARE_EXPERIMENTS_KEY,
  SPANS_KEY,
  SPANS_REST_ENDPOINT,
  TRACE_KEY,
  TRACES_KEY,
  TRACES_REST_ENDPOINT,
} from "@/api/api";
import { AxiosError } from "axios";
import { useToast } from "@/components/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import {
  generateUpdateMutation,
  setExperimentsCompareCache,
  setSpansCache,
  setTraceCache,
  setTracesCache,
} from "@/lib/feedback-scores";
import { useLoggedInUserName } from "@/store/AppStore";

type UseTraceFeedbackScoreSetMutationParams = {
  categoryName?: string;
  name: string;
  spanId?: string;
  traceId: string;
  value: number;
  reason?: string;
};

const useTraceFeedbackScoreSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const currentUserName = useLoggedInUserName();

  return useMutation({
    mutationFn: async ({
      categoryName,
      name,
      spanId,
      traceId,
      value,
      reason,
    }: UseTraceFeedbackScoreSetMutationParams) => {
      const endpoint = spanId
        ? `${SPANS_REST_ENDPOINT}${spanId}/feedback-scores`
        : `${TRACES_REST_ENDPOINT}${traceId}/feedback-scores`;

      const { data } = await api.put(endpoint, {
        category_name: categoryName,
        name,
        source: FEEDBACK_SCORE_TYPE.ui,
        value,
        reason,
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
    onMutate: async (params: UseTraceFeedbackScoreSetMutationParams) => {
      const traceParams = {
        traceId: params.traceId,
      };

      const updateMutation = generateUpdateMutation(
        {
          name: params.name,
          category_name: params.categoryName,
          value: params.value,
          source: FEEDBACK_SCORE_TYPE.ui,
          reason: params.reason,
        },
        currentUserName,
      );

      if (params.spanId) {
        // make optimistic update for spans
        await setSpansCache(
          queryClient,
          {
            traceId: params.traceId,
            spanId: params.spanId,
          },
          updateMutation,
        );
      } else {
        // make optimistic update for compare experiments
        await setExperimentsCompareCache(
          queryClient,
          traceParams,
          updateMutation,
        );

        // make optimistic update for traces
        await setTracesCache(queryClient, traceParams, updateMutation);

        // make optimistic update for trace
        await setTraceCache(queryClient, traceParams, updateMutation);
      }
    },
    onSettled: async (data, error, variables) => {
      if (variables.spanId) {
        await queryClient.invalidateQueries({ queryKey: [SPANS_KEY] });
        await queryClient.invalidateQueries({ queryKey: ["spans-columns"] });
        await queryClient.invalidateQueries({ queryKey: ["spans-statistic"] });
      }

      await queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
      await queryClient.invalidateQueries({ queryKey: ["traces-columns"] });
      await queryClient.invalidateQueries({ queryKey: ["traces-statistic"] });
      await queryClient.invalidateQueries({
        queryKey: ["experiment-items-statistic"],
      });

      await queryClient.invalidateQueries({
        queryKey: [TRACE_KEY, { traceId: variables.traceId }],
      });
      await queryClient.invalidateQueries({
        queryKey: ["experiments-columns"],
      });
      await queryClient.invalidateQueries({
        queryKey: ["experiment"],
      });
      await queryClient.invalidateQueries({
        queryKey: [COMPARE_EXPERIMENTS_KEY],
      });
    },
  });
};

export default useTraceFeedbackScoreSetMutation;
