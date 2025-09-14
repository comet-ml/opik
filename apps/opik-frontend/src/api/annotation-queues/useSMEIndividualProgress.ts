import { useQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api from "@/api/api";

type UseSMEIndividualProgressParams = {
  shareToken: string;
  smeId: string;
};

export type UseSMEIndividualProgressResponse = {
  total_items: number;
  completed_items: number;
  skipped_items: number;
  pending_items: number;
  next_item_id: string | null;
  next_item_type: string | null;
  completion_percentage: number;
};

export default function useSMEIndividualProgress(
  params: UseSMEIndividualProgressParams,
  options?: {
    enabled?: boolean;
    refetchInterval?: number;
  },
) {
  return useQuery({
    queryKey: ["sme-individual-progress", params.shareToken, params.smeId],
    queryFn: async () => {
      const { data } = await api.get<UseSMEIndividualProgressResponse>(
        `/v1/public/annotation-queues/${params.shareToken}/progress/${params.smeId}`,
      );
      return data;
    },
    enabled: options?.enabled ?? true,
    refetchInterval: options?.refetchInterval ?? 30000, // Refresh every 30 seconds
    retry: (failureCount, error) => {
      // Don't retry on 404 (invalid token/sme) or 403 (not public)
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



