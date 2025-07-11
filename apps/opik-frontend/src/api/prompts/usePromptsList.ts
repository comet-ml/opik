import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Prompt } from "@/types/prompts";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UsePromptsListParams = {
  workspaceName: string;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

type UsePromptsListResponse = {
  content: Prompt[];
  sortable_by: string[];
  total: number;
};

const getPromptsList = async (
  { signal }: QueryFunctionContext,
  { filters, sorting, search, size, page }: UsePromptsListParams,
) => {
  const { data } = await api.get(PROMPTS_REST_ENDPOINT, {
    signal,
    params: {
      ...processFilters(filters),
      ...processSorting(sorting),
      ...(search && { name: search }),
      size,
      page,
    },
  });

  return data;
};

export default function usePromptsList(
  params: UsePromptsListParams,
  options?: QueryConfig<UsePromptsListResponse>,
) {
  return useQuery({
    queryKey: ["prompts", params],
    queryFn: (context) => getPromptsList(context, params),
    ...options,
  });
}
