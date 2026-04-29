import { QueryFunctionContext, useInfiniteQuery } from "@tanstack/react-query";
import { AxiosError } from "axios";

import api, { AGENT_CONFIGS_KEY, AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { ConfigHistoryItem } from "@/types/agent-configs";
import useQueryErrorToast from "@/hooks/useQueryErrorToast";

const PAGE_SIZE = 50;

type UseConfigHistoryListInfiniteResponse = {
  content: ConfigHistoryItem[];
  page: number;
  size: number;
  total: number;
};

const EMPTY_RESPONSE: UseConfigHistoryListInfiniteResponse = {
  content: [],
  page: 1,
  size: PAGE_SIZE,
  total: 0,
};

const getConfigHistoryList = async (
  { signal }: QueryFunctionContext,
  { projectId, page }: { projectId: string; page: number },
): Promise<UseConfigHistoryListInfiniteResponse> => {
  try {
    const { data } = await api.get(
      `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/history/projects/${projectId}`,
      { signal, params: { page, size: PAGE_SIZE } },
    );

    return {
      ...data,
      content: data.content.map(
        (item: { envs?: string[] } & Omit<ConfigHistoryItem, "tags">) => ({
          ...item,
          tags: item.envs ?? [],
        }),
      ),
    };
  } catch (error) {
    if (error instanceof AxiosError && error.response?.status === 404) {
      return EMPTY_RESPONSE;
    }
    throw error;
  }
};

export default function useConfigHistoryListInfinite({
  projectId,
}: {
  projectId: string;
}) {
  const query = useInfiniteQuery<UseConfigHistoryListInfiniteResponse>({
    queryKey: [AGENT_CONFIGS_KEY, "history", { projectId }],
    queryFn: (ctx) =>
      getConfigHistoryList(ctx, { projectId, page: ctx.pageParam as number }),
    getNextPageParam: ({ page, size, total }) =>
      page * size < total ? page + 1 : undefined,
    initialPageParam: 1,
    refetchInterval: (query) => {
      if (query.state.status !== "success" || !query.state.data) return false;
      const isEmpty = !query.state.data.pages.some((p) => p.content.length > 0);
      return isEmpty ? 5000 : false;
    },
  });

  useQueryErrorToast({ isError: query.isError, error: query.error });

  return query;
}
