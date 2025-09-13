import { useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api from "@/api/api";
import { SMEAnnotationQueue } from "@/types/annotation-queues";

type UseSMEQueueParams = {
  shareToken: string;
};

export type UseSMEQueueResponse = SMEAnnotationQueue;

export default function useSMEQueue(
  params: UseSMEQueueParams,
  options?: {
    enabled?: boolean;
  },
) {
  return useQuery({
    queryKey: ["sme-queue", params.shareToken],
    queryFn: async () => {
      const { data } = await api.get<UseSMEQueueResponse>(
        `/v1/public/annotation-queues/${params.shareToken}`,
      );
      return data;
    },
    enabled: options?.enabled ?? true,
    retry: (failureCount, error) => {
      // Don't retry on 404 (invalid token) or 403 (not public)
      if (error instanceof AxiosError) {
        if (error.response?.status === 404 || error.response?.status === 403) {
          return false;
        }
      }
      return failureCount < 3;
    },
    staleTime: 5 * 60 * 1000, // 5 minutes
  });
}

