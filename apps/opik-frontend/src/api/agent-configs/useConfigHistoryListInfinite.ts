import { QueryFunctionContext, useInfiniteQuery } from "@tanstack/react-query";

import api, { AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { ConfigHistoryItem } from "@/types/agent-configs";

const PAGE_SIZE = 20;

type UseConfigHistoryListInfiniteResponse = {
  content: ConfigHistoryItem[];
  page: number;
  size: number;
  total: number;
};

const getConfigHistoryList = async (
  { signal }: QueryFunctionContext,
  { projectId, page }: { projectId: string; page: number },
): Promise<UseConfigHistoryListInfiniteResponse> => {
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
};

export default function useConfigHistoryListInfinite({
  projectId,
}: {
  projectId: string;
}) {
  return useInfiniteQuery<UseConfigHistoryListInfiniteResponse>({
    queryKey: [
      `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/history/projects`,
      { projectId },
    ],
    queryFn: (ctx) =>
      getConfigHistoryList(ctx, { projectId, page: ctx.pageParam as number }),
    getNextPageParam: ({ page, size, total }) =>
      page * size < total ? page + 1 : undefined,
    initialPageParam: 1,
  });
}
