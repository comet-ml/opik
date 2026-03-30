import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";
import { Filters } from "@/types/filters";
import {
  generateLogsSourceFilter,
  generateVisibilityFilters,
  processFilters,
} from "@/lib/filters";
import { LOGS_SOURCE } from "@/types/traces";
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
  fromTime?: string;
  toTime?: string;
  exclude?: string[];
  logsSource?: LOGS_SOURCE;
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
    fromTime,
    toTime,
    exclude,
    logsSource,
  }: UseTracesListParams,
) => {
  const additionalFilters = [
    ...generateVisibilityFilters(),
    ...(logsSource ? generateLogsSourceFilter(logsSource) : []),
  ];

  const { data } = await api.get<UseTracesListResponse>(TRACES_REST_ENDPOINT, {
    signal,
    params: {
      project_id: projectId,
      ...processFilters(filters, additionalFilters),
      ...processSorting(sorting),
      ...(search && { search }),
      size,
      page,
      truncate,
      ...(fromTime && { from_time: fromTime }),
      ...(toTime && { to_time: toTime }),
      ...(exclude &&
        exclude.length > 0 && { exclude: JSON.stringify(exclude) }),
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
