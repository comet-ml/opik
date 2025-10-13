import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { OPTIMIZATION_STUDIO_RUNS_KEY } from "@/api/api";
import { OptimizationStudioRun } from "@/types/optimization-studio";

type UseOptimizationStudioRunByIdParams = {
  runId: string;
  workspaceName: string;
};

const getOptimizationStudioRunById = async (
  { signal }: QueryFunctionContext,
  { runId, workspaceName }: UseOptimizationStudioRunByIdParams
) => {
  console.log("DEBUG API - Fetching run with id:", runId, "workspace:", workspaceName);

  const { data } = await api.get<OptimizationStudioRun>(
    `/v1/private/optimization-studio/runs/${runId}`,
    {
      signal,
      headers: {
        "Comet-Workspace": workspaceName,
      },
    }
  );

  console.log("DEBUG API - Received run data:", data);
  return data;
};

export default function useOptimizationStudioRunById(
  params: UseOptimizationStudioRunByIdParams,
  options?: {
    enabled?: boolean;
  }
) {
  return useQuery({
    queryKey: [OPTIMIZATION_STUDIO_RUNS_KEY, params.runId],
    queryFn: (context) => getOptimizationStudioRunById(context, params),
    ...options,
  });
}
