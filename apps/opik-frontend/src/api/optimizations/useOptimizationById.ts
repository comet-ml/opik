import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPTIMIZATION_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Optimization } from "@/types/optimizations";

export const getOptimizationById = async (
  { signal }: QueryFunctionContext,
  { optimizationId, includeStudioConfig }: UseOptimizationByIdParams,
) => {
  const { data } = await api.get(OPTIMIZATIONS_REST_ENDPOINT + optimizationId, {
    signal,
    params: {
      ...(includeStudioConfig && {
        include_studio_config: includeStudioConfig,
      }),
    },
  });

  return data;
};

type UseOptimizationByIdParams = {
  optimizationId: string;
  includeStudioConfig?: boolean;
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
