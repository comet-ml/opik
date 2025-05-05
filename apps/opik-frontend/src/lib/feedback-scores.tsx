import { QueryClient } from "@tanstack/react-query";
import { FEEDBACK_SCORE_TYPE, Trace, TraceFeedbackScore } from "@/types/traces";
import { AggregatedFeedbackScore } from "@/types/shared";
import {
  COMPARE_EXPERIMENTS_KEY,
  SPANS_KEY,
  TRACE_KEY,
  TRACES_KEY,
} from "@/api/api";
import { UseCompareExperimentsListResponse } from "@/api/datasets/useCompareExperimentsList";
import { UseTracesListResponse } from "@/api/traces/useTracesList";
import { UseSpansListResponse } from "@/api/traces/useSpansList";

export const FEEDBACK_SCORE_SOURCE_MAP = {
  [FEEDBACK_SCORE_TYPE.online_scoring]: "Online evaluation",
  [FEEDBACK_SCORE_TYPE.sdk]: "SDK",
  [FEEDBACK_SCORE_TYPE.ui]: "Human Review",
};

export const setExperimentsCompareCache = async (
  queryClient: QueryClient,
  params: { traceId: string },
  mutate: (
    feedbackScores?: TraceFeedbackScore[],
  ) => TraceFeedbackScore[] | undefined,
) => {
  const { queryKey } =
    queryClient.getQueryCache().find({
      exact: false,
      type: "active",
      queryKey: [COMPARE_EXPERIMENTS_KEY],
    }) ?? {};

  if (!queryKey) return;

  await queryClient.cancelQueries({ queryKey });

  queryClient.setQueryData(
    queryKey,
    (originalData: UseCompareExperimentsListResponse) => {
      return {
        ...originalData,
        content: originalData.content.map((experimentsCompare) => {
          return {
            ...experimentsCompare,
            experiment_items: experimentsCompare.experiment_items.map(
              (experimentItem) => {
                if (experimentItem.trace_id === params.traceId) {
                  return {
                    ...experimentItem,
                    feedback_scores: mutate(experimentItem.feedback_scores),
                  };
                }
                return experimentItem;
              },
            ),
          };
        }),
      };
    },
  );
};

export const setTracesCache = async (
  queryClient: QueryClient,
  params: { traceId: string },
  mutate: (
    feedbackScores?: TraceFeedbackScore[],
  ) => TraceFeedbackScore[] | undefined,
) => {
  // we can have few active request that we need to update
  // one on TracesSpansTab and another on ThreadDetailsPanel
  const query =
    queryClient.getQueryCache().findAll({
      exact: false,
      type: "active",
      queryKey: [TRACES_KEY],
    }) ?? {};

  query.map(async ({ queryKey }) => {
    await queryClient.cancelQueries({ queryKey });

    queryClient.setQueryData(
      queryKey,
      (originalData: UseTracesListResponse) => {
        return {
          ...originalData,
          content: originalData.content.map((trace) => {
            if (trace.id === params.traceId) {
              return {
                ...trace,
                feedback_scores: mutate(trace.feedback_scores),
              };
            }
            return trace;
          }),
        };
      },
    );
  });
};

export const setSpansCache = async (
  queryClient: QueryClient,
  params: { traceId: string; spanId: string },
  mutate: (
    feedbackScores?: TraceFeedbackScore[],
  ) => TraceFeedbackScore[] | undefined,
) => {
  const { queryKey } =
    queryClient.getQueryCache().find({
      exact: false,
      type: "active",
      queryKey: [SPANS_KEY, { traceId: params.traceId }],
    }) ?? {};

  if (!queryKey) return;

  await queryClient.cancelQueries({ queryKey });

  queryClient.setQueryData(queryKey, (originalData: UseSpansListResponse) => {
    return {
      ...originalData,
      content: originalData.content.map((span) => {
        if (span.id === params.spanId) {
          return {
            ...span,
            feedback_scores: mutate(span.feedback_scores),
          };
        }
        return span;
      }),
    };
  });
};

export const setTraceCache = async (
  queryClient: QueryClient,
  params: { traceId: string },
  mutate: (
    feedbackScores?: TraceFeedbackScore[],
  ) => TraceFeedbackScore[] | undefined,
) => {
  const { queryKey } =
    queryClient.getQueryCache().find({
      exact: false,
      type: "active",
      queryKey: [TRACE_KEY, { traceId: params.traceId }],
    }) ?? {};

  if (!queryKey) return;

  await queryClient.cancelQueries({ queryKey });

  queryClient.setQueryData(queryKey, (originalData: Trace) => {
    return {
      ...originalData,
      feedback_scores: mutate(originalData.feedback_scores),
    };
  });
};

export const generateUpdateMutation =
  (score: TraceFeedbackScore) => (feedbackScores?: TraceFeedbackScore[]) => {
    let retVal = feedbackScores || [];

    let isUpdated = false;
    retVal = retVal.map((feedbackScore) => {
      if (feedbackScore.name === score.name) {
        isUpdated = true;
        return { ...feedbackScore, ...score };
      }

      return feedbackScore;
    });

    if (!isUpdated) {
      retVal.push(score);
    }

    return retVal;
  };

export const generateDeleteMutation =
  (name: string) => (feedbackScores?: TraceFeedbackScore[]) =>
    (feedbackScores || []).filter(
      (feedbackScore) => feedbackScore.name !== name,
    );

export const categoryOptionLabelRenderer = (
  name: string,
  value?: number | string,
) => {
  if (!value) return name;

  return `${name} (${value})`;
};

export const getFeedbackScore = (
  feedbackScores: Array<TraceFeedbackScore | AggregatedFeedbackScore>,
  scoreName: string,
) => feedbackScores.find(({ name }) => name === scoreName);

export const getFeedbackScoreValue = (
  scores: Array<TraceFeedbackScore | AggregatedFeedbackScore>,
  scoreName: string,
) => getFeedbackScore(scores, scoreName)?.value;
