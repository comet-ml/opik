import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  DASHBOARD_CHARTS_KEY,
  DASHBOARDS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { 
  ChartDataRequest, 
  ChartDataResponse, 
  DashboardChart 
} from "@/types/dashboards";

type UseChartPreviewDataQueryParams = {
  dashboardId: string;
  chart: Partial<DashboardChart>;
  request: ChartDataRequest;
  workspaceName: string;
};

const getChartPreviewData = async (
  { signal }: QueryFunctionContext,
  { dashboardId, chart, request, workspaceName }: UseChartPreviewDataQueryParams,
) => {
  const { data } = await api.post(
    `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/charts/preview/data`,
    {
      chart,
      chart_data_request: request,
    },
    {
      signal,
      params: {
        workspace_name: workspaceName,
      },
    },
  );

  return data;
};

export default function useChartPreviewDataQuery(
  params: UseChartPreviewDataQueryParams,
  options?: QueryConfig<ChartDataResponse>,
) {
  return useQuery({
    queryKey: [DASHBOARD_CHARTS_KEY, "preview", params],
    queryFn: (context) => getChartPreviewData(context, params),
    ...options,
  });
}






