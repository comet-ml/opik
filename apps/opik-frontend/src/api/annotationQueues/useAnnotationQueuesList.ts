import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { ANNOTATION_QUEUES_REST_ENDPOINT } from "@/api/api";
import { AnnotationQueue } from "@/types/annotationQueues";
import { Filters } from "@/types/filters";

type UseAnnotationQueuesListParams = {
  workspaceName: string;
  projectId?: string;
  search?: string;
  page: number;
  size: number;
};

export type AnnotationQueuesFilters = Filters<
  Pick<AnnotationQueue, "status">
>;

const getAnnotationQueuesList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    projectId,
    search,
    page,
    size,
  }: UseAnnotationQueuesListParams,
) => {
  const { data } = await api.get(ANNOTATION_QUEUES_REST_ENDPOINT, {
    signal,
    params: {
      workspaceName,
      ...(projectId && { projectId }),
      ...(search && { search }),
      page,
      size,
    },
  });

  return data;
};

export default function useAnnotationQueuesList(
  params: UseAnnotationQueuesListParams,
  options?: object,
) {
  return useQuery({
    queryKey: ["annotation-queues", params],
    queryFn: (context) => getAnnotationQueuesList(context, params),
    ...options,
  });
}