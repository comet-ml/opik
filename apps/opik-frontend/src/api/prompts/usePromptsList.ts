import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Prompt } from "@/types/prompts";

type UsePromptsListParams = {
  workspaceName: string;
  search?: string;
  page: number;
  size: number;
};

type UsePromptsListResponse = {
  content: Prompt[];
  total: number;
};

const getPromptsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, search, size, page }: UsePromptsListParams,
) => {
  const { data } = await api.get(PROMPTS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(search && { name: search }),
      size,
      page,
    },
  });

  return data;
};

export default function usePromptsList(
  params: UsePromptsListParams,
  options?: QueryConfig<UsePromptsListResponse>,
) {
  return useQuery({
    queryKey: ["prompts", params],
    queryFn: (context) => getPromptsList(context, params),
    ...options,
  });
}
