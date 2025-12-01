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

  return data;
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
