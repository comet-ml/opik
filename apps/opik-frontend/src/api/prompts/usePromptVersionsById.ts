import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { PromptVersion } from "@/types/prompts";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filter } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UsePromptVersionsByIdParams = {
  promptId: string;
  page: number;
  size: number;
  sorting?: Sorting;
  filters?: Filter[];
  search?: string;
};

type UsePromptsVersionsByIdResponse = {
  content: PromptVersion[];
  page: number;
  size: number;
  total: number;
  sortable_by: string[];
};

const getPromptVersionsById = async (
  { signal }: QueryFunctionContext,
  {
    promptId,
    size,
    page,
    sorting,
    filters,
    search,
  }: UsePromptVersionsByIdParams,
) => {
  const { data } = await api.get(
    `${PROMPTS_REST_ENDPOINT}${promptId}/versions`,
    {
      signal,
      params: {
        ...processFilters(filters),
        ...processSorting(sorting),
        size,
        page,
        ...(search && { search }),
      },
    },
  );

  return data;
};

export default function usePromptVersionsById(
  params: UsePromptVersionsByIdParams,
  options?: QueryConfig<UsePromptsVersionsByIdResponse>,
) {
  return useQuery({
    queryKey: ["prompt-versions", params],
    queryFn: (context) => getPromptVersionsById(context, params),
    ...options,
  });
}
