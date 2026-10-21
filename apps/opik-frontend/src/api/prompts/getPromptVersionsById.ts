import { QueryFunctionContext } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { PromptVersion } from "@/types/prompts";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filter } from "@/types/filters";
import { processFilters } from "@/lib/filters";

export type GetPromptVersionsByIdParams = {
  promptId: string;
  page: number;
  size: number;
  sorting?: Sorting;
  filters?: Filter[];
  search?: string;
};

export type PromptVersionsByIdResponse = {
  content: PromptVersion[];
  page: number;
  size: number;
  total: number;
  sortable_by: string[];
};

export const getPromptVersionsById = async (
  { signal }: QueryFunctionContext,
  {
    promptId,
    size,
    page,
    sorting,
    filters,
    search,
  }: GetPromptVersionsByIdParams,
): Promise<PromptVersionsByIdResponse> => {
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
