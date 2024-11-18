import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { FEEDBACK_SCORES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { FeedbackScoreName } from "@/types/shared";

type UseExperimentsFeedbackScoresNamesParams = {
  experimentsId?: string[];
};

export type FeedbackScoresNamesResponse = {
  scores: FeedbackScoreName[];
};

const getFeedbackScoresNames = async (
  { signal }: QueryFunctionContext,
  { experimentsId }: UseExperimentsFeedbackScoresNamesParams,
) => {
  console.log(experimentsId);
  const { data } = await api.get<FeedbackScoresNamesResponse>(
    `${FEEDBACK_SCORES_REST_ENDPOINT}names`,
    {
      signal,
      params: {
        withOnlyExperiments: true,
      },
    },
  );

  return data;
};

export default function useExperimentsFeedbackScoresNames(
  params: UseExperimentsFeedbackScoresNamesParams,
  options?: QueryConfig<FeedbackScoresNamesResponse>,
) {
  return useQuery({
    queryKey: ["experiments-columns", params],
    queryFn: (context) => getFeedbackScoresNames(context, params),
    ...options,
  });
}
