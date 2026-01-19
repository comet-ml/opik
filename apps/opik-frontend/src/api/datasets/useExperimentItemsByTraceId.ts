import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";

export type ExperimentItemWithExperimentInfo = {
  id: string;
  experiment_id: string;
  dataset_item_id: string;
  trace_id: string;
  experiment_name: string;
  dataset_id: string;
  created_at: string;
  last_updated_at: string;
  created_by?: string;
  last_updated_by?: string;
};

export type UseExperimentItemsByTraceIdParams = {
  traceId: string;
};

const getExperimentItemsByTraceId = async (
  { signal }: QueryFunctionContext,
  { traceId }: UseExperimentItemsByTraceIdParams,
) => {
  const { data } = await api.get<ExperimentItemWithExperimentInfo[]>(
    `${EXPERIMENTS_REST_ENDPOINT}items/by-trace/${traceId}`,
    {
      signal,
    },
  );

  return data;
};

export default function useExperimentItemsByTraceId(
  params: UseExperimentItemsByTraceIdParams,
  options?: QueryConfig<ExperimentItemWithExperimentInfo[]>,
) {
  return useQuery({
    queryKey: ["experiment-items-by-trace", params],
    queryFn: (context) => getExperimentItemsByTraceId(context, params),
    ...options,
  });
}
