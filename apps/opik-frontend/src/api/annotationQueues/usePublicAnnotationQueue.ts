import { useQuery } from "@tanstack/react-query";
import api, { PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueue } from "@/types/annotationQueues";

type UsePublicAnnotationQueueParams = {
  queueId: string;
};

export default function usePublicAnnotationQueue({
  queueId,
}: UsePublicAnnotationQueueParams) {
  return useQuery({
    queryKey: ["public-annotation-queue", queueId],
    queryFn: async () => {
      const { data } = await api.get<AnnotationQueue>(
        `${PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}`
      );
      return data;
    },
    enabled: !!queueId,
  });
}