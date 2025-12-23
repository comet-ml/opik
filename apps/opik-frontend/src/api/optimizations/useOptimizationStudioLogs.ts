import { useQuery } from "@tanstack/react-query";
import axios from "axios";
import api, {
  OPTIMIZATION_KEY,
  OPTIMIZATIONS_STUDIO_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";

type UseOptimizationStudioLogsParams = {
  optimizationId: string;
};

export type OptimizationStudioLogsUrlResponse = {
  url: string;
  expires_at: string;
};

export type UseOptimizationStudioLogsResponse = {
  content: string;
  url: string | null;
  expiresAt: string | null;
};

const getOptimizationStudioLogs = async ({
  optimizationId,
}: UseOptimizationStudioLogsParams): Promise<UseOptimizationStudioLogsResponse> => {
  try {
    const { data: urlResponse } =
      await api.get<OptimizationStudioLogsUrlResponse>(
        `${OPTIMIZATIONS_STUDIO_REST_ENDPOINT}${optimizationId}/logs`,
      );

    const { data: logContent } = await axios.get<string>(urlResponse.url, {
      responseType: "text",
    });

    return {
      content: logContent,
      url: urlResponse.url,
      expiresAt: urlResponse.expires_at,
    };
  } catch {
    return {
      content: "",
      url: null,
      expiresAt: null,
    };
  }
};

export default function useOptimizationStudioLogs(
  params: UseOptimizationStudioLogsParams,
  options?: QueryConfig<UseOptimizationStudioLogsResponse>,
) {
  return useQuery({
    queryKey: [OPTIMIZATION_KEY, { ...params, type: "logs" }],
    queryFn: () => getOptimizationStudioLogs(params),
    ...options,
  });
}
