import { useMemo } from "react";

import useAnnotationQueueItems from "@/api/annotation-queues/useAnnotationQueueItems";
import { getAnnotationQueueItemId } from "@/lib/annotation-queues";
import {
  ANNOTATION_QUEUE_ITEM_SOURCE,
  AnnotationQueueItem,
} from "@/types/annotation-queues";
import { Thread, Trace } from "@/types/traces";

export type QueueItemSourceById = Record<string, ANNOTATION_QUEUE_ITEM_SOURCE>;

/**
 * How each of the given rows got into the queue, keyed by item id.
 *
 * A trace knows nothing about annotation queues, so the traces and threads APIs cannot carry this and
 * it takes a separate membership lookup joined by id. Only the visible rows are asked for. The id
 * comes from getAnnotationQueueItemId because a thread is a queue item under its thread_model_id
 * rather than the id its table shows.
 *
 * Both items tables use this, so the lookup and the join stay in one place.
 */
const useQueueItemSources = (
  annotationQueueId: string,
  rows: (Trace | Thread)[],
): QueueItemSourceById => {
  const itemIds = useMemo(
    () => rows.map(getAnnotationQueueItemId).filter(Boolean),
    [rows],
  );

  const { data } = useAnnotationQueueItems({ annotationQueueId, itemIds });

  return useMemo(
    () =>
      Object.fromEntries(
        (data?.content ?? []).map((item: AnnotationQueueItem) => [
          item.id,
          item.source,
        ]),
      ),
    [data],
  );
};

export default useQueueItemSources;
