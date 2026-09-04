import { useInfiniteQuery } from "@tanstack/react-query";
import { Sorting } from "@/types/sorting";
import { Filter } from "@/types/filters";
import {
  getPromptVersionsById,
  PromptVersionsByIdResponse,
} from "./getPromptVersionsById";

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
  refetchOnWindowFocus?: boolean;
};

export default function usePromptVersionsByIdInfinite(
  params: UsePromptVersionsByIdInfiniteParams,
  options?: UsePromptVersionsByIdInfiniteOptions,
) {
  return useInfiniteQuery<PromptVersionsByIdResponse>({
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
        size: PAGE_SIZE,
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
