import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, THREADS_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { generateSearchByIDFilters, processFilters } from "@/lib/filters";
import { Thread } from "@/types/traces";
import { Filters } from "@/types/filters";

type UseThreadListParams = {
  projectId: string;
  filters?: Filters;
  search?: string;
  page: number;
  size: number;
  truncate?: boolean;
};

export type UseThreadListResponse = {
  content: Thread[];
  total: number;
};

const getThreadList = async (
  { signal }: QueryFunctionContext,
  { projectId, filters, search, size, page, truncate }: UseThreadListParams,
) => {
  const { data } = await api.get<UseThreadListResponse>(
    `${TRACES_REST_ENDPOINT}threads`,
    {
      signal,
      params: {
        project_id: projectId,
        ...processFilters(filters, generateSearchByIDFilters(search)),
        size,
        page,
        truncate,
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
