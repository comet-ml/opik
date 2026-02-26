import { useInfiniteQuery } from "@tanstack/react-query";

import { OPTIMIZER_CONFIGS_REST_ENDPOINT } from "@/api/api";
import { ConfigHistoryItem } from "@/types/optimizer-configs";
import { MOCK_HISTORY_ITEMS } from "./useConfigHistoryList";

const PAGE_SIZE = 20;

type UseConfigHistoryListInfiniteResponse = {
  content: ConfigHistoryItem[];
  page: number;
  size: number;
  total: number;
};

// TODO: Replace mock with real API call
// const getConfigHistoryList = async (
//   { signal }: QueryFunctionContext,
//   { projectId, page }: { projectId: string; page: number },
// ) => {
//   const { data } = await api.get(`${OPTIMIZER_CONFIGS_REST_ENDPOINT}history/`, {
//     signal,
//     params: { project_id: projectId, page, size: PAGE_SIZE },
//   });
//   return data;
// };

export default function useConfigHistoryListInfinite({
  projectId,
}: {
  projectId: string;
}) {
  return useInfiniteQuery({
    queryKey: [`${OPTIMIZER_CONFIGS_REST_ENDPOINT}history/`, { projectId }],
    queryFn: ({ pageParam }: { pageParam: number }) => {
      const start = (pageParam - 1) * PAGE_SIZE;
      const end = start + PAGE_SIZE;
      const content = MOCK_HISTORY_ITEMS.slice(start, end);
      return Promise.resolve<UseConfigHistoryListInfiniteResponse>({
        content,
        page: pageParam,
        size: PAGE_SIZE,
        total: MOCK_HISTORY_ITEMS.length,
      });
    },
    getNextPageParam: ({ page, size, total }) =>
      page * size < total ? page + 1 : undefined,
    initialPageParam: 1,
  });
}
