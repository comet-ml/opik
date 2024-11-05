import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { CompactPromptVersion } from "@/types/prompts";

// ALEX
const FAKE_PROMPT_VERSIONS: CompactPromptVersion[] = [
  {
    id: "prompt_1234123412341234123412341241231",
    created_at: "2023-10-01T08:30:00Z",
  },
  {
    id: "prompt_2",
    created_at: "2023-10-02T10:15:00Z",
  },
  {
    id: "prompt_3",
    created_at: "2023-10-03T12:45:00Z",
  },
  {
    id: "prompt_4",
    created_at: "2023-10-04T15:20:00Z",
  },
  {
    id: "prompt_5",
    created_at: "2023-10-05T18:05:00Z",
  },
  {
    id: "prompt_6",
    created_at: "2023-10-06T09:40:00Z",
  },
  {
    id: "prompt_7",
    created_at: "2023-10-07T11:25:00Z",
  },
  {
    id: "prompt_8",
    created_at: "2023-10-08T13:55:00Z",
  },
  {
    id: "prompt_9",
    created_at: "2023-10-09T16:10:00Z",
  },
  {
    id: "prompt_10",
    created_at: "2023-10-10T19:30:00Z",
  },
];

const getPromptVersionsById = async (
  { signal }: QueryFunctionContext,
  { promptId, size, page, search }: UsePromptVersionsByIdParams,
) => {
  try {
    const { data } = await api.get(
      `${PROMPTS_REST_ENDPOINT}/${promptId}/versions`,
      {
        signal,
        params: {
          ...(search && { name: search }),
          size,
          page,
        },
      },
    );

    return data;
  } catch {
    return { content: FAKE_PROMPT_VERSIONS };
  }
};

type UsePromptVersionsByIdParams = {
  promptId: string;
  search?: string;
  page: number;
  size: number;
};

type UsePromptsVersionsByIdResponse = {
  content: CompactPromptVersion[];
  total: number;
};

export default function usePromptVersionsById(
  params: UsePromptVersionsByIdParams,
  options?: QueryConfig<UsePromptsVersionsByIdResponse>,
) {
  return useQuery({
    queryKey: ["prompt-versions", params],
    queryFn: (context) => getPromptVersionsById(context, params),
    ...options,
  });
}
