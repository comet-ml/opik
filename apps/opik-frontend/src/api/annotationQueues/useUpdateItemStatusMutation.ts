import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { QueueItemStatus } from "@/types/annotationQueues";

type UpdateItemStatusParams = {
  queueId: string;
  itemId: string;
  smeId: string;
  status: QueueItemStatus;
};

export default function useUpdateItemStatusMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      queueId,
      itemId,
      smeId,
      status,
    }: UpdateItemStatusParams) => {
      await api.put(
        `${PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}/items/${itemId}/status`,
        { status },
        {
          params: { smeId },
        }
      );
    },
    onSuccess: (_, variables) => {
      // Invalidate next item query to get the next item
      queryClient.invalidateQueries({
        queryKey: ["next-annotation-item", variables.queueId, variables.smeId],
      });
      
      // Invalidate queue data to update progress
      queryClient.invalidateQueries({
        queryKey: ["public-annotation-queue", variables.queueId],
      });
    },
  });
}