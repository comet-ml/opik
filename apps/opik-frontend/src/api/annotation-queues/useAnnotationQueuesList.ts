import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import { AnnotationQueueScope, AnnotationQueuesResponse } from "@/types/annotation-queues";
import api, { ANNOTATION_QUEUES_KEY, ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { QueryConfig } from "@/api/api";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseAnnotationQueuesListParams = {
  workspaceName: string;
  search?: string;
  scope?: AnnotationQueueScope;
  sorting?: Sorting;
  page: number;
  size: number;
};

export type UseAnnotationQueuesListResponse = AnnotationQueuesResponse;

export const getAnnotationQueuesList = async (
  { signal }: QueryFunctionContext,
  params: UseAnnotationQueuesListParams,
) => {
  const { data } = await api.get(ANNOTATION_QUEUES_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: params.workspaceName,
      ...processSorting(params.sorting),
      ...(params.search && { search: params.search }),
      ...(params.scope && { scope: params.scope }),
      size: params.size,
      page: params.page,
    },
  });

  return data;
};

export default function useAnnotationQueuesList(
  params: UseAnnotationQueuesListParams,
  options?: QueryConfig<UseAnnotationQueuesListResponse>,
) {
  return useQuery({
    queryKey: [ANNOTATION_QUEUES_KEY, params],
    queryFn: (context) => getAnnotationQueuesList(context, params),
    ...options,
  });
}

