import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { AGENT_CONFIGS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ConfigHistoryItem } from "@/types/agent-configs";
import { AGENT_CONFIGS_KEY } from "@/api/api";

export type UseConfigHistoryListParams = {
  projectId: string;
  page: number;
  size: number;
};

export type UseConfigHistoryListResponse = {
  content: ConfigHistoryItem[];
  page: number;
  size: number;
  total: number;
};

const getConfigHistoryList = async (
  { signal }: QueryFunctionContext,
  { projectId, page, size }: UseConfigHistoryListParams,
): Promise<UseConfigHistoryListResponse> => {
  const { data } = await api.get(
    `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/history/projects/${projectId}`,
    { signal, params: { page, size } },
  );

  return {
    ...data,
    content: data.content.map(
      (item: { envs?: string[] } & Omit<ConfigHistoryItem, "tags">) => ({
        ...item,
        tags: item.envs ?? [],
        values: [...(item.values ?? [])].sort((a, b) =>
          a.key.localeCompare(b.key),
        ),
      }),
    ),
  };
};

export default function useConfigHistoryList(
  params: UseConfigHistoryListParams,
  options?: QueryConfig<UseConfigHistoryListResponse>,
) {
  return useQuery({
    queryKey: [AGENT_CONFIGS_KEY, params],
    queryFn: (ctx) => getConfigHistoryList(ctx, params),
    ...options,
  });
}
