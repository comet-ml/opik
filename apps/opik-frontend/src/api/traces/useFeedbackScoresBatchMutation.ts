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
import { useToast } from "@/ui/use-toast";
import { FEEDBACK_SCORE_TYPE } from "@/types/traces";

export type FeedbackScoreBatchItemInput = {
  id: string;
  name: string;
  categoryName?: string;
  value: number | string;
  reason?: string;
  projectName?: string;
};

export type UseFeedbackScoresBatchMutationParams = {
  scores: FeedbackScoreBatchItemInput[];
  isSpanType?: boolean;
};

const BATCH_SIZE = 500;

const useFeedbackScoresBatchMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      scores,
      isSpanType,
    }: UseFeedbackScoresBatchMutationParams) => {
      const endpoint = isSpanType
        ? `${SPANS_REST_ENDPOINT}feedback-scores`
        : `${TRACES_REST_ENDPOINT}feedback-scores`;

      for (let i = 0; i < scores.length; i += BATCH_SIZE) {
        const chunk = scores.slice(i, i + BATCH_SIZE);
        const payload = {
          scores: chunk.map((item) => ({
            id: item.id,
            name: item.name,
            category_name: item.categoryName,
            value: item.value,
            reason: item.reason,
            project_name: item.projectName?.trim()
              ? item.projectName.trim()
              : undefined,
            source: FEEDBACK_SCORE_TYPE.ui,
          })),
        };

        let attempts = 0;
        const maxAttempts = 3;
        while (attempts < maxAttempts) {
          try {
            await api.put(endpoint, payload);
            break;
          } catch (error: unknown) {
            attempts++;
            const axiosErr = error as AxiosError;
            const statusErr = error as { status?: number };
            const isRateLimit =
              axiosErr?.response?.status === 429 || statusErr?.status === 429;
            if (isRateLimit && attempts < maxAttempts) {
              const backoffMs =
                Math.pow(2, attempts) * 300 + Math.random() * 100;
              await new Promise((resolve) => setTimeout(resolve, backoffMs));
              continue;
            }
            throw error;
          }
        }
      }
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
    onSettled: async (_data, _error, variables) => {
      if (variables?.isSpanType) {
        await queryClient.invalidateQueries({ queryKey: [SPANS_KEY] });
        await queryClient.invalidateQueries({ queryKey: ["spans-columns"] });
        await queryClient.invalidateQueries({ queryKey: ["spans-statistic"] });
      } else {
        await queryClient.invalidateQueries({ queryKey: [TRACES_KEY] });
        await queryClient.invalidateQueries({ queryKey: ["traces-columns"] });
        await queryClient.invalidateQueries({ queryKey: ["traces-statistic"] });
      }
      await queryClient.invalidateQueries({
        queryKey: ["experiment-items-statistic"],
      });
      await queryClient.invalidateQueries({
        queryKey: ["experiments-columns"],
      });

      if (variables?.scores) {
        for (const item of variables.scores) {
          if (!variables.isSpanType) {
            await queryClient.invalidateQueries({
              queryKey: [TRACE_KEY, { traceId: item.id }],
            });
          }
        }
      }
      await queryClient.invalidateQueries({ queryKey: [TRACE_KEY] });
      await queryClient.invalidateQueries({
        queryKey: [COMPARE_EXPERIMENTS_KEY],
      });
      await queryClient.invalidateQueries({
        queryKey: ["experiment"],
      });
    },
  });
};

export default useFeedbackScoresBatchMutation;
