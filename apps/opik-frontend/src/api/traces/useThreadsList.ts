import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";
import { Thread } from "@/types/traces";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseThreadListParams = {
  projectId: string;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
  truncate?: boolean;
  fromTime?: string;
  toTime?: string;
};

export type UseThreadListResponse = {
  content: Thread[];
  sortable_by: string[];
  total: number;
};

const getThreadList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    filters,
    sorting,
    search,
    size,
    page,
    truncate,
    fromTime,
    toTime,
  }: UseThreadListParams,
) => {
  const { data } = await api.get<UseThreadListResponse>(
    `${TRACES_REST_ENDPOINT}threads`,
    {
      signal,
      params: {
        project_id: projectId,
        ...processFilters(filters, generateSearchByIDFilters(search)),
        ...processSorting(sorting),
        size,
        page,
        truncate,
        ...(fromTime && { from_time: fromTime }),
        ...(toTime && { to_time: toTime }),
      },
    },
  );

  return data;
};

export default function useThreadList(
  params: UseThreadListParams,
  options?: QueryConfig<UseThreadListResponse>,
) {
  return useQuery({
    queryKey: [THREADS_KEY, params],
    queryFn: (context) => getThreadList(context, params),
    ...options,
  });
}
