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

/**
 * A single mutation hook for batch-setting feedback scores on both traces and
 * spans. Avoids the code duplication that arises from having two nearly-identical
 * hooks (one per entity type) and ensures consistent cache invalidation.
 *
 * Backend endpoints:
 *   PUT /v1/private/traces/feedback-scores  – scoreBatchOfTraces
 *   PUT /v1/private/spans/feedback-scores   – scoreBatchOfSpans
 *
 * Resolves baz-reviewer feedback on PR #6945:
 *  - Single hook instead of two copy-paste twins
 *  - Invalidates all relevant query keys on success (traces, spans, trace detail)
 */

export enum BATCH_ANNOTATION_ENTITY_TYPE {
  traces = "traces",
  spans = "spans",
}

export type FeedbackScoreBatchItem = {
  /** Trace id (for traces) or span id (for spans) */
  id: string;
  name: string;
  value: number;
  categoryName?: string;
  reason?: string;
};

type UseFeedbackScoreBatchSetMutationParams = {
  projectId: string;
  entityType: BATCH_ANNOTATION_ENTITY_TYPE;
  scores: FeedbackScoreBatchItem[];
};

const useFeedbackScoreBatchSetMutation = () => {
  const queryClient = useQueryClient();
  const { toast } = useToast();

  return useMutation({
    mutationFn: async ({
      entityType,
      scores,
    }: UseFeedbackScoreBatchSetMutationParams) => {
      const endpoint =
        entityType === BATCH_ANNOTATION_ENTITY_TYPE.spans
          ? `${SPANS_REST_ENDPOINT}feedback-scores`
          : `${TRACES_REST_ENDPOINT}feedback-scores`;

      const payload = {
        scores: scores.map((s) => ({
          id: s.id,
          name: s.name,
          value: s.value,
          source: FEEDBACK_SCORE_TYPE.ui,
          ...(s.categoryName && { category_name: s.categoryName }),
          ...(s.reason && { reason: s.reason }),
        })),
      };

      const { data } = await api.put(endpoint, payload);
      return data;
    },
    onError: (error: AxiosError) => {
      const message = get(
        error,
        ["response", "data", "message"],
        error.message,
      );

      toast({
        title: "Error annotating items",
        description: message,
        variant: "destructive",
      });
    },
    onSuccess: (_data, { projectId }) => {
      // Invalidate list-level queries so counts and score columns refresh.
      void queryClient.invalidateQueries({
        queryKey: [TRACES_KEY, { projectId }],
      });
      void queryClient.invalidateQueries({
        queryKey: [SPANS_KEY, { projectId }],
      });
      // Invalidate single-item queries so open detail panels update too.
      void queryClient.invalidateQueries({
        queryKey: [TRACE_KEY],
      });
      // Invalidate experiment comparison if visible.
      void queryClient.invalidateQueries({
        queryKey: [COMPARE_EXPERIMENTS_KEY],
      });
    },
    onSettled: (_, __, { projectId }) => {
      // Always refetch columns/statistics so header aggregates are up to date.
      // Mirrors the pattern used in existing single-item mutations.
      void queryClient.invalidateQueries({
        queryKey: ["traces-columns", { projectId }],
      });
      void queryClient.invalidateQueries({
        queryKey: ["traces-statistic", { projectId }],
      });
      void queryClient.invalidateQueries({
        queryKey: ["spans-columns", { projectId }],
      });
      void queryClient.invalidateQueries({
        queryKey: ["spans-statistic", { projectId }],
      });
    },
  });
};

export default useFeedbackScoreBatchSetMutation;
