import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Prompt } from "@/types/prompts";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseProjectPromptsListParams = {
  projectId: string;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

type UseProjectPromptsListResponse = {
  content: Prompt[];
  sortable_by: string[];
  total: number;
};

const getProjectPromptsList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    filters,
    sorting,
    search,
    size,
    page,
  }: UseProjectPromptsListParams,
) => {
  const { data } = await api.get(
    `${PROJECTS_REST_ENDPOINT}${projectId}/prompts`,
    {
      signal,
      params: {
        ...processFilters(filters),
        ...processSorting(sorting),
        ...(search && { name: search }),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useProjectPromptsList(
  params: UseProjectPromptsListParams,
  options?: QueryConfig<UseProjectPromptsListResponse>,
) {
  return useQuery({
    queryKey: ["project-prompts", params],
    queryFn: (context) => getProjectPromptsList(context, params),
    ...options,
  });
}
