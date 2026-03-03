import { useQuery } from "@tanstack/react-query";
import {
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

export const MOCK_HISTORY_ITEMS: ConfigHistoryItem[] = [
  {
    id: "0195f1d4-1bf2-7b61-9ad9-1c71b4c9d0a7",
    description: "Reduce temperature for more determinism",
    createdBy: "user_123",
    createdAt: "2026-02-28T10:27:05.901Z",
    tags: ["production", "v2.1"],
  },
  {
    id: "0195f1d4-2af3-8c72-0be0-2d82c5d0e1b8",
    description: "Switch to gpt-4.1-mini model",
    createdBy: "user_456",
    createdAt: "2026-02-25T15:42:30.000Z",
    tags: ["staging"],
  },
  {
    id: "0195f1d4-3bg4-9d83-1cf1-3e93d6e1f2c9",
    description: "Increase max tokens to 2048",
    createdBy: "user_123",
    createdAt: "2026-02-23T09:15:00.000Z",
    tags: ["experiment"],
  },
  {
    id: "0195f1d4-4ch5-0e94-2dg2-4f04e7f2g3d0",
    description: "Add system prompt v3",
    createdBy: "user_789",
    createdAt: "2026-02-16T14:00:00.000Z",
    tags: ["production", "v2.0"],
  },
  {
    id: "0195f1d4-5di6-1f05-3eh3-5g15f8g3h4e1",
    description: "Initial blueprint configuration",
    createdBy: "user_123",
    createdAt: "2026-02-15T08:30:00.000Z",
    tags: [],
  },
];

// TODO: Replace mock with real API call
// const getConfigHistoryList = async (
//   { signal }: QueryFunctionContext,
//   { projectId, page, size }: UseConfigHistoryListParams,
// ) => {
//   const { data } = await api.get(`${OPTIMIZER_CONFIGS_REST_ENDPOINT}history/`, {
//     signal,
//     params: {
//       project_id: projectId,
//       page,
//       size,
//     },
//   });
//
//   return data;
// };

export default function useConfigHistoryList(
  params: UseConfigHistoryListParams,
  options?: QueryConfig<UseConfigHistoryListResponse>,
) {
  return useQuery({
    queryKey: [`${OPTIMIZER_CONFIGS_REST_ENDPOINT}history/`, params],
    queryFn: () => {
      const start = (params.page - 1) * params.size;
      const end = start + params.size;
      const content = MOCK_HISTORY_ITEMS.slice(start, end);

      return Promise.resolve<UseConfigHistoryListResponse>({
        content,
        page: params.page,
        size: params.size,
        total: MOCK_HISTORY_ITEMS.length,
      });
    },
    ...options,
  });
}
