import { useQuery } from "@tanstack/react-query";
import api, { ANNOTATION_QUEUES_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { AnnotationQueueLocksResponse } from "@/types/annotation-queues";

export const ANNOTATION_QUEUE_LOCKS_KEY = "annotation-queue-locks";

const getAnnotationQueueLocks = async (
  queueId: string,
  signal?: AbortSignal,
) => {
  const { data } = await api.get<AnnotationQueueLocksResponse>(
    `${ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}/locks`,
    { signal },
  );
  return data;
};

export default function useAnnotationQueueLocks(
  params: { queueId: string },
  options?: QueryConfig<AnnotationQueueLocksResponse>,
) {
  return useQuery({
    queryKey: [ANNOTATION_QUEUE_LOCKS_KEY, { queueId: params.queueId }],
    queryFn: ({ signal }) => getAnnotationQueueLocks(params.queueId, signal),
    ...options,
  });
}
