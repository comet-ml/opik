import { useQuery } from "@tanstack/react-query";
import api, { PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { QueueItem } from "@/types/annotationQueues";

type UseNextAnnotationItemParams = {
  queueId: string;
  smeId: string;
};

export default function useNextAnnotationItem({
  queueId,
  smeId,
}: UseNextAnnotationItemParams) {
  return useQuery({
    queryKey: ["next-annotation-item", queueId, smeId],
    queryFn: async () => {
      const { data } = await api.get<QueueItem>(
        `${PUBLIC_ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}/next-item`,
        {
          params: { smeId },
        }
      );
      return data;
    },
    enabled: !!queueId && !!smeId,
    refetchOnWindowFocus: false,
    staleTime: 0, // Always fetch fresh data for annotation flow
  });
}