import { QueryFunctionContext, useQuery } from "@tanstack/react-query";

import api, { QueryConfig } from "@/api/api";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";
import { SystemMetricSeries } from "@/types/system-metrics";

type SystemMetricSeriesParams = {
  projectId: string;
  instanceId: string;
  metricName: string;
  from: string;
  to: string;
};

const getSystemMetricSeries = async (
  { signal }: QueryFunctionContext,
  params: SystemMetricSeriesParams,
) => {
  const { data } = await api.get<SystemMetricSeries>(
    `/v1/private/projects/${params.projectId}/system-metrics`,
    {
      signal,
      params: {
        instance_id: params.instanceId,
        metric_name: params.metricName,
        from: params.from,
        to: params.to,
      },
    },
  );
  return data;
};

export default function useSystemMetricSeries(
  params: SystemMetricSeriesParams,
  options?: QueryConfig<SystemMetricSeries>,
) {
  const query = useQuery({
    queryKey: ["system-metric-series", params],
    queryFn: (context) => getSystemMetricSeries(context, params),
    ...options,
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });
  return query;
}
