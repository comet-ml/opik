import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_REST_ENDPOINT } from "@/api/api";
import { Trace } from "@/types/traces";
import { Filters } from "@/types/filters";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";

type UseTracesListParams = {
  projectId: string;
  filters?: Filters;
  search?: string;
  page: number;
  size: number;
};

export type UseTracesListResponse = {
  content: Trace[];
  total: number;
};

const getTracesList = async (
  { signal }: QueryFunctionContext,
  { projectId, filters, search, size, page }: UseTracesListParams,
) => {
  const { data } = await api.get<UseTracesListResponse>(TRACES_REST_ENDPOINT, {
    signal,
    params: {
      project_id: projectId,
      ...processFilters(filters, generateSearchByIDFilters(search)),
      size,
      page,
    },
  });

  return data;
};

export default function useTracesList(
  params: UseTracesListParams,
  options?: QueryConfig<UseTracesListResponse>,
) {
  return useQuery({
    queryKey: ["traces", params],
    queryFn: (context) => getTracesList(context, params),
    ...options,
  });
}
