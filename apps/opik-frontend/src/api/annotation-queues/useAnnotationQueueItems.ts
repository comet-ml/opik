import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
  QueryConfig,
} from "@/api/api";
import { AnnotationQueueItem } from "@/types/annotation-queues";

type UseAnnotationQueueItemsParams = {
  annotationQueueId: string;
  itemIds: string[];
};

type UseAnnotationQueueItemsResponse = {
  content: AnnotationQueueItem[];
};

/**
 * Queue membership metadata for the items currently on screen.
 *
 * <p>A lookup rather than a listing: the items table is driven by the traces API with its own sorting and
 * filtering, so asking for a page of membership would return rows that cannot be aligned with the rows
 * being displayed. The caller passes the ids it is showing and joins by id.
 *
 * <p>Ids with no membership are simply absent from the response.
 */
const getAnnotationQueueItems = async (
  { signal }: QueryFunctionContext,
  { annotationQueueId, itemIds }: UseAnnotationQueueItemsParams,
) => {
  const { data } = await api.post<UseAnnotationQueueItemsResponse>(
    `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}/items/search`,
    { ids: itemIds },
    { signal },
  );

  return data;
};

export default function useAnnotationQueueItems(
  params: UseAnnotationQueueItemsParams,
  options?: QueryConfig<UseAnnotationQueueItemsResponse>,
) {
  return useQuery({
    // Prefixed with ANNOTATION_QUEUES_KEY so the add/remove item mutations, which invalidate that
    // prefix, refresh this lookup too.
    queryKey: [ANNOTATION_QUEUES_KEY, params, "items"],
    queryFn: (context) => getAnnotationQueueItems(context, params),
    ...options,
    enabled: (options?.enabled ?? true) && params.itemIds.length > 0,
  });
}
