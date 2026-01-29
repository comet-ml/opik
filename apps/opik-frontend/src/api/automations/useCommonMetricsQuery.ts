import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AUTOMATIONS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { CommonMetricList } from "@/types/automations";

const COMMON_METRICS_KEY = "common-metrics";

const getCommonMetrics = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<CommonMetricList>(
    `${AUTOMATIONS_REST_ENDPOINT}evaluators/common-metrics`,
    { signal },
  );

  return data;
};

/**
 * Hook to fetch available common metrics from the Python SDK.
 * Uses aggressive caching since metrics rarely change (bundled with backend).
 */
export default function useCommonMetricsQuery(
  options?: QueryConfig<CommonMetricList>,
) {
  return useQuery({
    queryKey: [COMMON_METRICS_KEY, {}],
    queryFn: getCommonMetrics,
    // Aggressive caching - metrics are static and bundled with the backend
    staleTime: Infinity,
    gcTime: 24 * 60 * 60 * 1000, // 24 hours
    refetchOnMount: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
    ...options,
  });
}
