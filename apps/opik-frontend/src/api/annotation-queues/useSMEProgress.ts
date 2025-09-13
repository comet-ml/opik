import { useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api from "@/api/api";
import { SMEQueueProgress } from "@/types/annotation-queues";

type UseSMEProgressParams = {
  shareToken: string;
};

export type UseSMEProgressResponse = SMEQueueProgress;

export default function useSMEProgress(
  params: UseSMEProgressParams,
  options?: {
    enabled?: boolean;
    refetchInterval?: number;
  },
) {
  return useQuery({
    queryKey: ["sme-queue-progress", params.shareToken],
    queryFn: async () => {
      const { data } = await api.get<UseSMEProgressResponse>(
        `/v1/public/annotation-queues/${params.shareToken}/progress`,
      );
      return data;
    },
    enabled: options?.enabled ?? true,
    refetchInterval: options?.refetchInterval ?? 30000, // Refresh every 30 seconds
    retry: (failureCount, error) => {
      // Don't retry on 404 (invalid token) or 403 (not public)
      if (error instanceof AxiosError) {
        if (error.response?.status === 404 || error.response?.status === 403) {
          return false;
        }
      }
      return failureCount < 3;
    },
    staleTime: 10 * 1000, // 10 seconds
  });
}

