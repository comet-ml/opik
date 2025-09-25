import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  ANNOTATION_QUEUES_REST_ENDPOINT,
  ANNOTATION_QUEUES_KEY,
  QueryConfig,
} from "@/api/api";
import { AnnotationQueue } from "@/types/annotation-queues";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";
import { generateProjectFilters, processFilters } from "@/lib/filters";
import { processSorting } from "@/lib/sorting";

type UseAnnotationQueuesListParams = {
  workspaceName?: string;
  filters?: Filters;
  sorting?: Sorting;
  projectId?: string;
  search?: string;
  page?: number;
  size?: number;
};

export type UseAnnotationQueuesListResponse = {
  content: AnnotationQueue[];
  sortable_by: string[];
  total: number;
};

const getAnnotationQueuesList = async (
  { signal }: QueryFunctionContext,
  {
    filters,
    sorting,
    projectId,
    search,
    page,
    size,
  }: UseAnnotationQueuesListParams,
) => {
  const { data } = await api.get<UseAnnotationQueuesListResponse>(
    ANNOTATION_QUEUES_REST_ENDPOINT,
    {
      signal,
      params: {
        ...processFilters(filters, generateProjectFilters(projectId)),
        ...processSorting(sorting),
        ...(search && { name: search }),
        ...(page && { page }),
        ...(size && { size }),
      },
    },
  );

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
