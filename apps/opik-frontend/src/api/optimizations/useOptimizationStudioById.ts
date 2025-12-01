import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPTIMIZATION_STUDIO_KEY,
  OPTIMIZATIONS_STUDIO_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OptimizationStudio } from "@/types/optimizations";

export const getOptimizationStudioById = async (
  { signal }: QueryFunctionContext,
  { optimizationId }: UseOptimizationStudioByIdParams,
) => {
  const { data } = await api.get(
    OPTIMIZATIONS_STUDIO_REST_ENDPOINT + optimizationId,
    {
      signal,
    },
  );

  return data;
};

type UseOptimizationStudioByIdParams = {
  optimizationId: string;
};

export default function useOptimizationStudioById(
  params: UseOptimizationStudioByIdParams,
  options?: QueryConfig<OptimizationStudio>,
) {
  return useQuery({
    queryKey: [OPTIMIZATION_STUDIO_KEY, params],
    queryFn: (context) => getOptimizationStudioById(context, params),
    ...options,
  });
}
