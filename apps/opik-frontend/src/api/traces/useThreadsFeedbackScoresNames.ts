import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_REST_ENDPOINT } from "@/api/api";
import { FeedbackScoreName } from "@/types/shared";

type UseThreadsFeedbackScoresNamesParams = {
  projectId?: string;
};

export type FeedbackScoresNamesResponse = {
  scores: FeedbackScoreName[];
};

const getFeedbackScoresNames = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseThreadsFeedbackScoresNamesParams,
) => {
  const { data } = await api.get<FeedbackScoresNamesResponse>(
    `${TRACES_REST_ENDPOINT}threads/feedback-scores/names`,
    {
      signal,
      params: {
        ...(projectId && { project_id: projectId }),
      },
    },
  );

  return data;
};

export default function useThreadsFeedbackScoresNames(
  params: UseThreadsFeedbackScoresNamesParams,
  options?: QueryConfig<FeedbackScoresNamesResponse>,
) {
  return useQuery({
    queryKey: ["threads-columns", params],
    queryFn: (context) => getFeedbackScoresNames(context, params),
    ...options,
  });
}
