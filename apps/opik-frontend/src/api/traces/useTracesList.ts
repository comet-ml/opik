import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";
import { Filters } from "@/types/filters";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseTracesListParams = {
  projectId: string;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
  truncate?: boolean;
};

export type UseTracesListResponse = {
  content: Trace[];
  sortable_by: string[];
  total: number;
};

const getTracesList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    filters,
    sorting,
    search,
    size,
    page,
    truncate,
  }: UseTracesListParams,
) => {
  const { data } = await api.get<UseTracesListResponse>(TRACES_REST_ENDPOINT, {
    signal,
    params: {
      project_id: projectId,
      ...processFilters(filters, generateSearchByIDFilters(search)),
      ...processSorting(sorting),
      size,
      page,
      truncate,
    },
  });

  return data;
};

export default function useTracesList(
  params: UseTracesListParams,
  options?: QueryConfig<UseTracesListResponse>,
) {
  return useQuery({
    queryKey: [TRACES_KEY, params],
    queryFn: (context) => getTracesList(context, params),
    ...options,
  });
}
