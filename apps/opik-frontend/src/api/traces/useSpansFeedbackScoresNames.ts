import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { FEEDBACK_SCORES_REST_ENDPOINT, QueryConfig } from "@/api/api";
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
  { projectId }: UseSpansFeedbackScoresNamesParams,
) => {
  const { data } = await api.get<FeedbackScoresNamesResponse>(
    `${FEEDBACK_SCORES_REST_ENDPOINT}names`,
    {
      signal,
      params: {
        ...(projectId && { project_id: projectId }),
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
