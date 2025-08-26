import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { QUEUE_ITEMS_REST_ENDPOINT } from "@/api/api";
import { QueueItemsBatch } from "@/types/annotationQueues";

type AddItemsToQueueParams = {
  queueId: string;
  items: Array<{
    item_type: "trace" | "thread";
    item_id: string;
  }>;
};

export default function useAddItemsToQueueMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({ queueId, items }: AddItemsToQueueParams) => {
      const requestBody: QueueItemsBatch = {
        items,
      };

      await api.post(QUEUE_ITEMS_REST_ENDPOINT, requestBody, {
        params: { queueId },
      });
    },
    onSuccess: (_, variables) => {
      // Invalidate annotation queues list to update item counts
      queryClient.invalidateQueries({
        queryKey: ["annotation-queues"],
      });
      
      // Invalidate specific queue data
      queryClient.invalidateQueries({
        queryKey: ["annotation-queue", variables.queueId],
      });
    },
  });
}