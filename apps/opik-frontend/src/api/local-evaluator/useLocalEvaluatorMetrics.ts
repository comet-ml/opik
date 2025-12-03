import { useQuery, UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";
import { LocalMetricsListResponse } from "@/types/local-evaluator";

interface UseLocalEvaluatorMetricsParams {
  url: string;
}

export const useLocalEvaluatorMetrics = (
  { url }: UseLocalEvaluatorMetricsParams,
  options?: Omit<
    UseQueryOptions<LocalMetricsListResponse, Error>,
    "queryKey" | "queryFn"
  >,
) => {
  return useQuery<LocalMetricsListResponse, Error>({
    queryKey: ["localEvaluatorMetrics", url],
    queryFn: async () => {
      const response = await axios.get<LocalMetricsListResponse>(
        `${url}/api/v1/evaluation/metrics`,
        { timeout: 5000 },
      );
      return response.data;
    },
    staleTime: 30000, // 30 seconds
    retry: false,
    ...options,
  });
};

export default useLocalEvaluatorMetrics;
