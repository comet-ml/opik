import { useMutation } from "@tanstack/react-query";
import api, { ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueueItemLockResponse } from "@/types/annotation-queues";

type LockItemParams = {
  queueId: string;
  itemId: string;
};

const lockItem = async ({ queueId, itemId }: LockItemParams) => {
  const { data } = await api.put<AnnotationQueueItemLockResponse>(
    `${ANNOTATION_QUEUES_REST_ENDPOINT}${queueId}/items/${itemId}/lock`,
  );
  return data;
};

export function useAnnotationQueueItemLockMutation() {
  return useMutation({ mutationFn: lockItem });
}
