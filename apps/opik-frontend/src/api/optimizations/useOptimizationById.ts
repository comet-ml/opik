import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPTIMIZATION_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Optimization } from "@/types/optimizations";

export const getOptimizationById = async (
  { signal }: QueryFunctionContext,
  { optimizationId }: UseOptimizationByIdParams,
) => {
  const { data } = await api.get(OPTIMIZATIONS_REST_ENDPOINT + optimizationId, {
    signal,
  });

  return {
    id: optimizationId,
    name: "Some optimization from BE",
    dataset_id: data?.dataset_id,
    dataset_name: data?.dataset_name,
    metadata: {},
    feedback_scores: [{ name: "levenshtein_ratio_metric", value: 0.92 }],
    objective_name: "levenshtein_ratio_metric",
    num_trials: 25,
    status: "running",
    created_at: data?.created_at,
    created_by: data?.created_by,
    last_updated_at: data?.last_updated_at,
    last_updated_by: data?.last_updated_by,
  } as Optimization;
};

type UseOptimizationByIdParams = {
  optimizationId: string;
};

export default function useOptimizationById(
  params: UseOptimizationByIdParams,
  options?: QueryConfig<Optimization>,
) {
  return useQuery({
    queryKey: [OPTIMIZATION_KEY, params],
    queryFn: (context) => getOptimizationById(context, params),
    ...options,
  });
}
