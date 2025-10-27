import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARD_CHARTS_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { ChartDataRequest, ChartDataResponse } from "@/types/dashboards";

type UseChartDataQueryParams = {
  dashboardId: string;
  chartId: string;
  request: ChartDataRequest;
  workspaceName: string;
};

const getChartData = async (
  { signal }: QueryFunctionContext,
  { dashboardId, chartId, request, workspaceName }: UseChartDataQueryParams,
) => {
  const { data } = await api.post(
    `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/charts/${chartId}/data`,
    request,
    {
      signal,
      params: {
        workspace_name: workspaceName,
      },
    },
  );

  return data;
};

export default function useChartDataQuery(
  params: UseChartDataQueryParams,
  options?: QueryConfig<ChartDataResponse>,
) {
  return useQuery({
    queryKey: [DASHBOARD_CHARTS_KEY, "data", params],
    queryFn: (context) => getChartData(context, params),
    ...options,
  });
}



