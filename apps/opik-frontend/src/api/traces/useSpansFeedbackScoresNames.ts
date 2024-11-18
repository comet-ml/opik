import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { FEEDBACK_SCORES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { FeedbackScoreName } from "@/types/shared";
import { SPAN_TYPE } from "@/types/traces";

type UseFeedbackScoresNamesParams = {
  projectId?: string;
  type?: SPAN_TYPE;
};

export type FeedbackScoresNamesResponse = {
  scores: FeedbackScoreName[];
};

const getFeedbackScoresNames = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseFeedbackScoresNamesParams,
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
  params: UseFeedbackScoresNamesParams,
  options?: QueryConfig<FeedbackScoresNamesResponse>,
) {
  return useQuery({
    queryKey: ["traces-feedback-scores-names", params],
    queryFn: (context) => getFeedbackScoresNames(context, params),
    ...options,
  });
}
