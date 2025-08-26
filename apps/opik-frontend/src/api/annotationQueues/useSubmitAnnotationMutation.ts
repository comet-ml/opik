import { useMutation, useQueryClient } from "@tanstack/react-query";
import api, { PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { Annotation } from "@/types/annotationQueues";

type SubmitAnnotationParams = {
  queueId: string;
  itemId: string;
  smeId: string;
  metrics: Record<string, any>;
  comment?: string;
};

export default function useSubmitAnnotationMutation() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async ({
      queueId,
      itemId,
      smeId,
      metrics,
      comment,
    }: SubmitAnnotationParams) => {
      const { data } = await api.post<Annotation>(
        `${PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}/items/${itemId}/annotations`,
        {
          metrics,
          comment,
        },
        {
          params: { smeId },
        }
      );
      return data;
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