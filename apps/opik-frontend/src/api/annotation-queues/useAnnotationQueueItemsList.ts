import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { ANNOTATION_QUEUE_KEY, ANNOTATION_QUEUES_REST_ENDPOINT, QueryConfig } from "@/api/api";

type UseAnnotationQueueItemsListParams = {
  workspaceName: string;
  annotationQueueId: string;
  page: number;
  size: number;
};

type AnnotationQueueItemsResponse = {
  content: any[]; // TODO: Define proper types for queue items
  total: number;
  sortable_by: string[];
};

const getAnnotationQueueItemsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, annotationQueueId, page, size }: UseAnnotationQueueItemsListParams,
) => {
  const { data } = await api.get<AnnotationQueueItemsResponse>(
    `${ANNOTATION_QUEUES_REST_ENDPOINT}${annotationQueueId}/items`,
    {
      signal,
      params: {
        workspace_name: workspaceName,
        page,
        size,
      },
    },
  );

  return data;
};

export default function useAnnotationQueueItemsList(
  params: UseAnnotationQueueItemsListParams,
  options?: QueryConfig<AnnotationQueueItemsResponse>,
) {
  return useQuery({
    queryKey: [ANNOTATION_QUEUE_KEY, { type: "items", ...params }],
    queryFn: (context) => getAnnotationQueueItemsList(context, params),
    ...options,
    enabled: Boolean(params.workspaceName && params.annotationQueueId),
  });
}
