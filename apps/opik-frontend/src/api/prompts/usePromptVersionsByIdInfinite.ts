import { QueryFunctionContext, useInfiniteQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT } from "@/api/api";
import { PromptVersion } from "@/types/prompts";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filter } from "@/types/filters";
import { processFilters } from "@/lib/filters";

const PAGE_SIZE = 25;

type UsePromptVersionsByIdInfiniteParams = {
  promptId: string;
  sorting?: Sorting;
  filters?: Filter[];
  search?: string;
};

type UsePromptVersionsByIdInfiniteOptions = {
  enabled?: boolean;
  refetchInterval?: number;
};

type UsePromptVersionsByIdInfiniteResponse = {
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
    page,
    sorting,
    filters,
    search,
  }: UsePromptVersionsByIdInfiniteParams & { page: number },
): Promise<UsePromptVersionsByIdInfiniteResponse> => {
  const { data } = await api.get(
    `${PROMPTS_REST_ENDPOINT}${promptId}/versions`,
    {
      signal,
      params: {
        ...processFilters(filters),
        ...processSorting(sorting),
        size: PAGE_SIZE,
        page,
        ...(search && { search }),
      },
    },
  );

  return data;
};

export default function usePromptVersionsByIdInfinite(
  params: UsePromptVersionsByIdInfiniteParams,
  options?: UsePromptVersionsByIdInfiniteOptions,
) {
  return useInfiniteQuery<UsePromptVersionsByIdInfiniteResponse>({
    // Shares the "prompt-versions" key prefix (and the same
    // `{ promptId, ... }` params shape) with usePromptVersionsById so the
    // mutation hooks that invalidate that prefix (create/delete/deploy a
    // version) also invalidate this list — otherwise the sidebar goes stale
    // after every write. Doesn't collide in the cache: this hook's params
    // never include `page`/`size`, which usePromptVersionsById always does.
    queryKey: ["prompt-versions", params],
    queryFn: (context) =>
      getPromptVersionsById(context, {
        ...params,
        page: context.pageParam as number,
      }),
    // `size` on the response is the actual item count returned (not the
    // requested page size), so it shrinks on the last page and hits 0 past
    // it — `page * size` is not a valid "items seen so far" once that
    // happens. Sum each page's real content length instead.
    getNextPageParam: (lastPage, allPages) => {
      const fetchedCount = allPages.reduce(
        (sum, p) => sum + p.content.length,
        0,
      );
      return fetchedCount < lastPage.total ? allPages.length + 1 : undefined;
    },
    initialPageParam: 1,
    ...options,
  });
}
