import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  AUTOMATIONS_REST_ENDPOINT,
  EVENT_AUTOMATIONS_KEY,
  QueryConfig,
} from "@/api/api";
import { Recommendation } from "@/types/event-automations";

type UseRecommendationsListParams = {
  projectId: string;
};

const getRecommendationsList = async (
  { signal }: QueryFunctionContext,
  { projectId }: UseRecommendationsListParams,
) => {
  const { data } = await api.get<Recommendation[]>(
    `${AUTOMATIONS_REST_ENDPOINT}recommendations`,
    { signal, params: { project_id: projectId } },
  );

  return data;
};

export default function useRecommendationsList(
  params: UseRecommendationsListParams,
  options?: QueryConfig<Recommendation[]>,
) {
  return useQuery({
    queryKey: [
      EVENT_AUTOMATIONS_KEY,
      { resource: "recommendations", ...params },
    ],
    queryFn: (context) => getRecommendationsList(context, params),
    ...options,
  });
}
