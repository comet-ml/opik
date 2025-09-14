import { useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api from "@/api/api";

type UseSMEQueueItemDataParams = {
  shareToken: string;
  itemId: string;
};

export type UseSMEQueueItemDataResponse = {
  id: string;
  type: string;
  input: unknown;
  output: unknown;
  metadata: unknown;
};

export default function useSMEQueueItemData(
  params: UseSMEQueueItemDataParams,
  options?: {
    enabled?: boolean;
  },
) {
  return useQuery({
    queryKey: ["sme-queue-item-data", params.shareToken, params.itemId],
    queryFn: async () => {
      const { data } = await api.get<UseSMEQueueItemDataResponse>(
        `/v1/public/annotation-queues/${params.shareToken}/items/${params.itemId}/data`,
      );
      return data;
    },
    enabled: options?.enabled ?? true,
    retry: (failureCount, error) => {
      // Don't retry on 404 (invalid token/item) or 403 (not public)
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
