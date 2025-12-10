import { useQuery, UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";

interface UseLocalEvaluatorHealthcheckParams {
  url: string;
}

export const useLocalEvaluatorHealthcheck = (
  { url }: UseLocalEvaluatorHealthcheckParams,
  options?: Omit<UseQueryOptions<boolean, Error>, "queryKey" | "queryFn">,
) => {
  return useQuery<boolean, Error>({
    queryKey: ["localEvaluatorHealthcheck", url],
    queryFn: async () => {
      try {
        const response = await axios.get(`${url}/healthcheck`, {
          timeout: 3000,
        });
        return response.data?.status === "ok";
      } catch {
        return false;
      }
    },
    staleTime: 5000, // 5 seconds
    retry: false,
    refetchInterval: 10000, // Poll every 10 seconds
    ...options,
  });
};

export default useLocalEvaluatorHealthcheck;
