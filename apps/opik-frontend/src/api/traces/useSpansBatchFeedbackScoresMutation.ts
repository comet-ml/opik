import api, { SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { useToast } from "@/components/ui/use-toast";
import {
  generateUpdateMutation,
  setSpansCache,
} from "@/lib/feedback-scores";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { AxiosError } from "axios";
import get from "lodash/get";

type UseSpansBatchFeedbackScoresParams = {
  projectId: string;
  spans: { id: string; traceId: string }[];
  name: string;
  value: number;
  categoryName?: string;
  reason?: string;
};

const useSpansBatchFeedbackScoresMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      projectId,
      spans,
      name,
      value,
      categoryName,
      reason,
    }: UseSpansBatchFeedbackScoresParams) => {
      const body = {
        scores: spans.map((span) => ({
          id: span.id,
          project_id: projectId,
          name,
          value,
          ...(categoryName && { category_name: categoryName }),
          ...(reason && { reason }),
          source: FEEDBACK_SCORE_TYPE.ui,
        })),
      };

      const { data } = await api.put(
        `${SPANS_REST_ENDPOINT}feedback-scores`,
        body,
      );
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
    onSuccess: (_, vars) => {
      toast({
        title: "Success",
        description: `Annotated ${vars.spans.length} spans`,
      });
    },
    onMutate: async (params: UseSpansBatchFeedbackScoresParams) => {
      const updateMutation = generateUpdateMutation({
        name: params.name,
        category_name: params.categoryName,
        value: params.value,
        source: FEEDBACK_SCORE_TYPE.ui,
        reason: params.reason,
      });

      await Promise.all(
        params.spans.map((span) =>
          setSpansCache(
            queryClient,
            { traceId: span.traceId, spanId: span.id },
            updateMutation,
          ),
        ),
      );
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: [SPANS_KEY] });
      await queryClient.invalidateQueries({ queryKey: ["spans-columns"] });
      await queryClient.invalidateQueries({ queryKey: ["spans-statistic"] });
    },
  });
};

export default useSpansBatchFeedbackScoresMutation;


