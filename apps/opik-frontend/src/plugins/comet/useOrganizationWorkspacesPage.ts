import {
  InfiniteData,
  QueryFunctionContext,
  useInfiniteQuery,
  UseInfiniteQueryOptions,
} from "@tanstack/react-query";

import api from "@/plugins/comet/api";
import { Workspace } from "@/plugins/comet/types";

const PAGE_SIZE = 50;

// The backend answers this endpoint by visibility: an organization admin gets every workspace of the
// organization, a plain member gets only the ones they belong to. The `default` ("Personal") and
// reserved AI-spend workspaces are excluded server-side, so `total` describes exactly the rows the
// switcher renders. Default sort is workspace name ascending.
export type OrganizationWorkspacesPage = {
  data: Workspace[];
  total: number;
};

const EMPTY_PAGE: OrganizationWorkspacesPage = { data: [], total: 0 };

type Params = {
  organizationId: string;
  search?: string;
};

type QueryKey = ["organization-workspaces-page", Params];

// Everything the hook owns (key, fetcher, paging) is fixed; callers may still tune the rest of the
// infinite-query surface (enabled, staleTime, …) with the correct types for an infinite query.
type Options = Omit<
  UseInfiniteQueryOptions<
    OrganizationWorkspacesPage,
    Error,
    InfiniteData<OrganizationWorkspacesPage>,
    OrganizationWorkspacesPage,
    QueryKey,
    number
  >,
  "queryKey" | "queryFn" | "getNextPageParam" | "initialPageParam"
>;

const getOrganizationWorkspacesPage = async (
  { signal }: QueryFunctionContext,
  { organizationId, search, page }: Params & { page: number },
): Promise<OrganizationWorkspacesPage> => {
  const { data } = await api.get<OrganizationWorkspacesPage>(
    "/workspaces/paged",
    {
      signal,
      params: {
        organizationId,
        page,
        pageSize: PAGE_SIZE,
        withoutExtendedData: true,
        ...(search ? { search } : {}),
      },
    },
  );
  return data ?? EMPTY_PAGE;
};

// One bounded request per slice, appended as the caller scrolls — the switcher never downloads the
// whole organization. `search` reaches the server, so a name far outside the first slice is still
// found; changing it starts a fresh query rather than filtering a partial list in the browser.
export default function useOrganizationWorkspacesPage(
  { organizationId, search }: Params,
  options?: Options,
) {
  return useInfiniteQuery({
    queryKey: ["organization-workspaces-page", { organizationId, search }] as QueryKey,
    queryFn: (ctx) =>
      getOrganizationWorkspacesPage(ctx, {
        organizationId,
        search,
        page: ctx.pageParam as number,
      }),
    initialPageParam: 1,
    getNextPageParam: (lastPage, allPages) => {
      const loaded = allPages.reduce((sum, p) => sum + p.data.length, 0);
      return loaded < lastPage.total ? allPages.length + 1 : undefined;
    },
    ...options,
    enabled: Boolean(organizationId) && (options?.enabled ?? true),
  });
}

export { PAGE_SIZE };
