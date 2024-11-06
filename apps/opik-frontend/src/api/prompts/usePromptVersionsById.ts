import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { PromptVersion } from "@/types/prompts";

type UsePromptVersionsByIdParams = {
  promptId: string;
  search?: string;
  page: number;
  size: number;
};

type UsePromptsVersionsByIdResponse = {
  content: PromptVersion[];
  total: number;
};

const getPromptVersionsById = async (
  { signal }: QueryFunctionContext,
  { promptId, size, page, search }: UsePromptVersionsByIdParams,
) => {
  const { data } = await api.get(
    `${PROMPTS_REST_ENDPOINT}${promptId}/versions`,
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
