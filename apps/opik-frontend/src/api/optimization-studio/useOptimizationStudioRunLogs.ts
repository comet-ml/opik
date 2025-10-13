import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { OPTIMIZATION_STUDIO_RUNS_KEY } from "@/api/api";
import { LogItem } from "@/types/shared";

type UseOptimizationStudioRunLogsParams = {
  runId: string;
  workspaceName: string;
  page?: number;
  size?: number;
};

type OptimizationStudioRunLogsResponse = {
  content: LogItem[];
  page: number;
  size: number;
  total: number;
};

const getOptimizationStudioRunLogs = async (
  { signal }: QueryFunctionContext,
  { runId, workspaceName, page = 1, size = 10000 }: UseOptimizationStudioRunLogsParams
) => {
  const { data } = await api.get<OptimizationStudioRunLogsResponse>(
    `/v1/private/optimization-studio/runs/${runId}/logs`,
    {
      signal,
      params: {
        page,
        size,
      },
      headers: {
        "Comet-Workspace": workspaceName,
      },
    }
  );

  return data;
};

export default function useOptimizationStudioRunLogs(
  params: UseOptimizationStudioRunLogsParams,
  options?: {
    enabled?: boolean;
    refetchInterval?: number;
  }
) {
  return useQuery({
    queryKey: [OPTIMIZATION_STUDIO_RUNS_KEY, "logs", params],
    queryFn: (context) => getOptimizationStudioRunLogs(context, params),
    ...options,
  });
}
