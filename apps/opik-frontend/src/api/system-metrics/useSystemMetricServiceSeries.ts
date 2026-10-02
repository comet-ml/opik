import { QueryFunctionContext, useQuery } from "@tanstack/react-query";

import api, { QueryConfig } from "@/api/api";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";
import { SystemMetricSeries } from "@/types/system-metrics";

type SystemMetricServiceSeriesParams = {
  projectId: string;
  instanceIds: string[];
  metricName: string;
  from: string;
  to: string;
};

const getSystemMetricServiceSeries = async (
  { signal }: QueryFunctionContext,
  params: SystemMetricServiceSeriesParams,
): Promise<SystemMetricSeries> => {
  const responses = await Promise.all(
    params.instanceIds.map((instanceId) =>
      api.get<SystemMetricSeries>(
        `/v1/private/projects/${params.projectId}/system-metrics`,
        {
          signal,
          params: {
            instance_id: instanceId,
            metric_name: params.metricName,
            from: params.from,
            to: params.to,
          },
        },
      ),
    ),
  );
  const series = responses.map((response) => response.data);

  return {
    project_id: params.projectId,
    service_instance_id: "*",
    metric_name: params.metricName,
    unit: series[0]?.unit ?? "{request}",
    from: params.from,
    to: params.to,
    truncated: series.some((item) => item.truncated),
    points: series
      .flatMap((item) => item.points)
      .sort((left, right) => left.timestamp.localeCompare(right.timestamp)),
  };
};

export default function useSystemMetricServiceSeries(
  params: SystemMetricServiceSeriesParams,
  options?: QueryConfig<SystemMetricSeries>,
) {
  const query = useQuery({
    queryKey: ["system-metric-service-series", params],
    queryFn: (context) => getSystemMetricServiceSeries(context, params),
    ...options,
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });
  return query;
}
