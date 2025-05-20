import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, SPANS_REST_ENDPOINT } from "@/api/api";
import { FeedbackScoreName } from "@/types/shared";
import { SPAN_TYPE } from "@/types/traces";

type UseSpansFeedbackScoresNamesParams = {
  projectId?: string;
  type?: SPAN_TYPE;
};

export type FeedbackScoresNamesResponse = {
  scores: FeedbackScoreName[];
};

const getFeedbackScoresNames = async (
  { signal }: QueryFunctionContext,
  { projectId, type }: UseSpansFeedbackScoresNamesParams,
) => {
  const { data } = await api.get<FeedbackScoresNamesResponse>(
    `${SPANS_REST_ENDPOINT}feedback-scores/names`,
    {
      signal,
      params: {
        ...(projectId && { project_id: projectId }),
        ...(type && { type }),
      },
    },
  );

  return data;
};

export default function useSpansFeedbackScoresNames(
  params: UseSpansFeedbackScoresNamesParams,
  options?: QueryConfig<FeedbackScoresNamesResponse>,
) {
  return useQuery({
    queryKey: ["spans-columns", params],
    queryFn: (context) => getFeedbackScoresNames(context, params),
    ...options,
  });
}
