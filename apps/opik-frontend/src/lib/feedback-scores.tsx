import { QueryClient } from "@tanstack/react-query";
import {
  FEEDBACK_SCORE_TYPE,
  FeedbackScoreValueByAuthorMap,
  Trace,
  TraceFeedbackScore,
} from "@/types/traces";
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
import { isUndefined } from "lodash";

export const FEEDBACK_SCORE_SOURCE_MAP = {
  [FEEDBACK_SCORE_TYPE.online_scoring]: "Online evaluation",
  [FEEDBACK_SCORE_TYPE.sdk]: "SDK",
  [FEEDBACK_SCORE_TYPE.ui]: "Human Review",
};

export function getIsMultiValueFeedbackScore(
  value_by_author: unknown,
): value_by_author is FeedbackScoreValueByAuthorMap {
  return Boolean(
    value_by_author &&
      typeof value_by_author === "object" &&
      !Array.isArray(value_by_author) &&
      Object.keys(value_by_author).length > 1,
  );
}

export function hasValuesByAuthor(
  score: TraceFeedbackScore,
): score is TraceFeedbackScore & {
  value_by_author: FeedbackScoreValueByAuthorMap;
} {
  return Boolean(
    score && typeof score === "object" && "value_by_author" in score,
  );
}

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
  (score: TraceFeedbackScore, author?: string) =>
  (feedbackScores?: TraceFeedbackScore[]) => {
    let retVal = feedbackScores || [];

    let isUpdated = false;
    retVal = retVal.map((feedbackScore) => {
      if (feedbackScore.name === score.name) {
        isUpdated = true;

        if (hasValuesByAuthor(feedbackScore) && author) {
          const updatedValueByAuthor: FeedbackScoreValueByAuthorMap = {
            ...feedbackScore.value_by_author,
            [author]: {
              value: score.value,
              reason: score.reason,
              category_name: score.category_name,
              source: score.source || FEEDBACK_SCORE_TYPE.ui,
              last_updated_at: new Date().toISOString(),
            },
          };

          const aggregated =
            aggregateMultiAuthorFeedbackScore(updatedValueByAuthor);

          return {
            ...feedbackScore,
            ...aggregated,
            value_by_author: updatedValueByAuthor,
            last_updated_at: new Date().toISOString(),
            last_updated_by: author,
            created_by: author,
          };
        }

        return { ...feedbackScore, ...score };
      }

      return feedbackScore;
    });

    if (!isUpdated) {
      retVal.push({ ...score, created_by: author });
    }

    return retVal;
  };

export const generateDeleteMutation =
  (name: string, author?: string) =>
  (feedbackScores?: TraceFeedbackScore[]): TraceFeedbackScore[] => {
    const retVal = feedbackScores || [];
    if (!author) {
      return retVal.filter((feedbackScore) => feedbackScore.name !== name);
    }

    return retVal
      .map((feedbackScore) => {
        if (feedbackScore.name === name && hasValuesByAuthor(feedbackScore)) {
          const updatedValueByAuthor = { ...feedbackScore.value_by_author };
          delete updatedValueByAuthor[author];

          if (Object.keys(updatedValueByAuthor).length === 0) {
            return null;
          }

          const aggregated =
            aggregateMultiAuthorFeedbackScore(updatedValueByAuthor);

          return {
            ...feedbackScore,
            ...aggregated,
            value_by_author: updatedValueByAuthor,
            last_updated_at: new Date().toISOString(),
            last_updated_by: author,
          };
        }

        return feedbackScore;
      })
      .filter(
        (feedbackScore): feedbackScore is TraceFeedbackScore =>
          feedbackScore !== null,
      );
  };

export const categoryOptionLabelRenderer = (
  name: string,
  value?: number | string,
) => {
  if (isUndefined(value)) return name;

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

export const extractReasonsFromValueByAuthor = (
  valueByAuthor?: FeedbackScoreValueByAuthorMap,
) => {
  if (!valueByAuthor) return [];

  return Object.entries(valueByAuthor)
    .map(([author, { reason, last_updated_at, value }]) => ({
      author,
      reason: reason || "",
      lastUpdatedAt: last_updated_at,
      value,
    }))
    .filter((v) => v.reason.trim());
};

export const aggregateMultiAuthorFeedbackScore = (
  valueByAuthor: FeedbackScoreValueByAuthorMap,
) => {
  const allValues = Object.values(valueByAuthor);

  if (allValues.length === 0) {
    return {
      value: 0,
      category_name: undefined,
      reason: undefined,
    };
  }

  const aggregatedValue =
    allValues.length === 1
      ? allValues[0].value
      : allValues.reduce((sum, item) => sum + item.value, 0) / allValues.length;

  const aggregatedCategoryName = allValues
    .map((item) => item.category_name)
    .filter(Boolean)
    .join(", ");

  const aggregatedReason = allValues
    .map((item) => item.reason)
    .filter(Boolean)
    .join(", ");

  return {
    value: aggregatedValue,
    category_name: aggregatedCategoryName || undefined,
    reason: aggregatedReason || undefined,
  };
};
