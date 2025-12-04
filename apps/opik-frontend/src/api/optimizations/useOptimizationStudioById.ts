import {
  QueryFunctionContext,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import api, {
  OPTIMIZATION_STUDIO_KEY,
  OPTIMIZATIONS_KEY,
  OPTIMIZATIONS_STUDIO_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { OptimizationStudio, OPTIMIZATION_STATUS } from "@/types/optimizations";

type UseOptimizationStudioByIdParams = {
  optimizationId: string;
};

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

export default function useOptimizationStudioById(
  params: UseOptimizationStudioByIdParams,
  options?: QueryConfig<OptimizationStudio>,
) {
  const queryClient = useQueryClient();

  return useQuery({
    queryKey: [OPTIMIZATION_STUDIO_KEY, params],
    queryFn: async (context) => {
      const previousData = queryClient.getQueryData<OptimizationStudio>([
        OPTIMIZATION_STUDIO_KEY,
        params,
      ]);
      const newData = await getOptimizationStudioById(context, params);

      // invalidate optimizations list when status changes to/from RUNNING
      if (previousData?.status !== newData.status) {
        const wasRunning = previousData?.status === OPTIMIZATION_STATUS.RUNNING;
        const isRunning = newData.status === OPTIMIZATION_STATUS.RUNNING;

        if (wasRunning || isRunning) {
          queryClient.invalidateQueries({ queryKey: [OPTIMIZATIONS_KEY] });
        }
      }

      return newData;
    },
    ...options,
  });
}
