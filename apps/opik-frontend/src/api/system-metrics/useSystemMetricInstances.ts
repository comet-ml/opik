import { QueryFunctionContext, useQuery } from "@tanstack/react-query";

import api, { QueryConfig } from "@/api/api";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";
import { SystemMetricInstances } from "@/types/system-metrics";

const getSystemMetricInstances = async (
  { signal }: QueryFunctionContext,
  projectId: string,
) => {
  const { data } = await api.get<SystemMetricInstances>(
    `/v1/private/projects/${projectId}/system-metrics/instances`,
    { signal },
  );
  return data;
};

export default function useSystemMetricInstances(
  projectId: string,
  options?: QueryConfig<SystemMetricInstances>,
) {
  const query = useQuery({
    queryKey: ["system-metric-instances", { projectId }],
    queryFn: (context) => getSystemMetricInstances(context, projectId),
    refetchInterval: 15_000,
    ...options,
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });
  return query;
}
