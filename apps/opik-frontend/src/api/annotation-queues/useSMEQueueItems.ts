import { useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api from "@/api/api";

type UseSMEQueueItemsParams = {
  shareToken: string;
  page?: number;
  size?: number;
};

export type UseSMEQueueItemsResponse = {
  content: Array<Record<string, unknown>>;
  total: number;
  sortable_by: string[];
};

export default function useSMEQueueItems(
  params: UseSMEQueueItemsParams,
  options?: {
    enabled?: boolean;
  },
) {
  const { shareToken, page = 1, size = 10 } = params;

  return useQuery({
    queryKey: ["sme-queue-items", shareToken, page, size],
    queryFn: async () => {
      const { data } = await api.get<UseSMEQueueItemsResponse>(
        `/v1/public/annotation-queues/${shareToken}/items`,
        {
          params: { page, size },
        },
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
    staleTime: 2 * 60 * 1000, // 2 minutes
  });
}

