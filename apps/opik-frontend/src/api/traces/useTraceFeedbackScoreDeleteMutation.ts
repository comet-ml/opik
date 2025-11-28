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
import { useLoggedInUserName } from "@/store/AppStore";
import {
  generateDeleteMutation,
  setExperimentsCompareCache,
  setSpansCache,
  setTraceCache,
  setTraceSpanFeedbackScoresCache,
  setTracesCache,
} from "@/lib/feedback-scores";

type UseTraceFeedbackScoreDeleteMutationParams = {
  name: string;
  spanId?: string;
  traceId: string;
  author?: string;
};

const useTraceFeedbackScoreDeleteMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();
  const currentUserName = useLoggedInUserName();

  return useMutation({
    mutationFn: async ({
      name,
      spanId,
      traceId,
      author,
    }: UseTraceFeedbackScoreDeleteMutationParams) => {
      const endpoint = spanId
        ? `${SPANS_REST_ENDPOINT}${spanId}/feedback-scores/delete`
        : `${TRACES_REST_ENDPOINT}${traceId}/feedback-scores/delete`;

      const { data } = await api.post(endpoint, {
        name,
        author: author ?? currentUserName,
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
    onMutate: async (params: UseTraceFeedbackScoreDeleteMutationParams) => {
      const traceParams = {
        traceId: params.traceId,
      };

      const authorToUse = params.author ?? currentUserName;
      const deleteMutation = generateDeleteMutation(params.name, authorToUse);

      if (params.spanId) {
        const spanId = params.spanId; // TypeScript guard: ensure spanId is defined
        // make optimistic update for spans
        await setSpansCache(
          queryClient,
          {
            traceId: params.traceId,
            spanId,
          },
          deleteMutation,
        );
        // Also update trace's span_feedback_scores aggregation
        // Only update if we have an author (required for cache key matching)
        if (authorToUse) {
          await setTraceSpanFeedbackScoresCache(queryClient, {
            traceId: params.traceId,
            spanId,
            name: params.name,
            author: authorToUse,
          });
        }
      } else {
        // make optimistic update for compare experiments
        await setExperimentsCompareCache(
          queryClient,
          traceParams,
          deleteMutation,
        );

        // make optimistic update for traces
        await setTracesCache(queryClient, traceParams, deleteMutation);

        // make optimistic update for trace
        await setTraceCache(queryClient, traceParams, deleteMutation);
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

export default useTraceFeedbackScoreDeleteMutation;
