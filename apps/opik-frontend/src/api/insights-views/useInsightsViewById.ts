import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  INSIGHTS_VIEW_KEY,
  INSIGHTS_VIEWS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Dashboard } from "@/types/dashboard";

type UseInsightsViewByIdParams = {
  dashboardId: string;
};

const getInsightsViewById = async (
  { signal }: QueryFunctionContext,
  { dashboardId }: UseInsightsViewByIdParams,
) => {
  const { data } = await api.get(
    `${INSIGHTS_VIEWS_REST_ENDPOINT}${dashboardId}`,
    {
      signal,
    },
  );

  return data;
};

export default function useInsightsViewById(
  params: UseInsightsViewByIdParams,
  options?: QueryConfig<Dashboard>,
) {
  return useQuery({
    queryKey: [INSIGHTS_VIEW_KEY, params],
    queryFn: (context) => getInsightsViewById(context, params),
    ...options,
  });
}
