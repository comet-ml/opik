import { QueryConfig } from "@/types/api";
import { useQuery } from "@tanstack/react-query";
import api, { DASHBOARDS_REST_ENDPOINT } from "@/api/api";
import { DashboardChart } from "@/types/dashboards";

type UseChartByIdParams = {
  dashboardId: string;
  chartId: string;
};

const useChartById = (
  { dashboardId, chartId }: UseChartByIdParams,
  options?: QueryConfig<DashboardChart>
) => {
  return useQuery({
    queryKey: ["chart", dashboardId, chartId],
    queryFn: async () => {
      const { data } = await api.get<DashboardChart>(
        `${DASHBOARDS_REST_ENDPOINT}${dashboardId}/charts/${chartId}`
      );
      return data;
    },
    ...options,
  });
};

export default useChartById;



