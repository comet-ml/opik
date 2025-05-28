import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { FeedbackScoreName } from "@/types/shared";

type UseExperimentsFeedbackScoresNamesParams = {
  experimentsIds?: string[];
};

export type FeedbackScoresNamesResponse = {
  scores: FeedbackScoreName[];
};

const getFeedbackScoresNames = async (
  { signal }: QueryFunctionContext,
  { experimentsIds }: UseExperimentsFeedbackScoresNamesParams,
) => {
  const { data } = await api.get<FeedbackScoresNamesResponse>(
    `${EXPERIMENTS_REST_ENDPOINT}feedback-scores/names`,
    {
      signal,
      params: {
        ...(experimentsIds && {
          experiment_ids: JSON.stringify(experimentsIds),
        }),
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
