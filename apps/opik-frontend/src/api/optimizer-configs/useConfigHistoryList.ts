import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  OPTIMIZER_CONFIGS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { ConfigHistoryItem } from "@/types/optimizer-configs";

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
) => {
  const { data } = await api.get(`${OPTIMIZER_CONFIGS_REST_ENDPOINT}history/`, {
    signal,
    params: {
      project_id: projectId,
      page,
      size,
    },
  });

  return data;
};

export default function useConfigHistoryList(
  params: UseConfigHistoryListParams,
  options?: QueryConfig<UseConfigHistoryListResponse>,
) {
  return useQuery({
    queryKey: [`${OPTIMIZER_CONFIGS_REST_ENDPOINT}history/`, params],
    queryFn: (context) => getConfigHistoryList(context, params),
    ...options,
  });
}
