import {
  QueryFunctionContext,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback } from "react";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { PromptWithLatestVersion } from "@/types/prompts";

const getPromptById = async (
  { signal }: QueryFunctionContext,
  { promptId }: UsePromptByIdParams,
) => {
  const { data } = await api.get(`${PROMPTS_REST_ENDPOINT}${promptId}`, {
    signal,
  });

  return data;
};

type UsePromptByIdParams = {
  promptId: string;
};

export default function usePromptById(
  params: UsePromptByIdParams,
  options?: QueryConfig<PromptWithLatestVersion>,
) {
  return useQuery({
    queryKey: ["prompt", params],
    queryFn: (context) => getPromptById(context, params),
    ...options,
  });
}

export function useFetchPrompt() {
  const queryClient = useQueryClient();

  return useCallback(
    async (params: UsePromptByIdParams): Promise<PromptWithLatestVersion> => {
      return queryClient.fetchQuery({
        queryKey: ["prompt", params],
        queryFn: (context) => getPromptById(context, params),
        staleTime: 5 * 60 * 1000,
      });
    },
    [queryClient],
  );
}
