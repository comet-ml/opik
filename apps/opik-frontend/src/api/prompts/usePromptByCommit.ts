import {
  QueryFunctionContext,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback } from "react";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { PromptByCommit } from "@/types/prompts";

type UsePromptByCommitParams = {
  commitId: string;
};

export const getPromptByCommit = async (
  { signal }: QueryFunctionContext,
  { commitId }: UsePromptByCommitParams,
) => {
  const { data } = await api.get(
    `${PROMPTS_REST_ENDPOINT}by-commit/${commitId}`,
    { signal },
  );

  return data as PromptByCommit;
};

export default function usePromptByCommit(
  params: UsePromptByCommitParams,
  options?: QueryConfig<PromptByCommit>,
) {
  return useQuery({
    queryKey: ["prompt-by-commit", params],
    queryFn: (context) => getPromptByCommit(context, params),
    ...options,
  });
}

export function useFetchPromptByCommit() {
  const queryClient = useQueryClient();

  return useCallback(
    async (params: UsePromptByCommitParams): Promise<PromptByCommit> => {
      return queryClient.fetchQuery({
        queryKey: ["prompt-by-commit", params],
        queryFn: (context) => getPromptByCommit(context, params),
        staleTime: 5 * 60 * 1000,
      });
    },
    [queryClient],
  );
}
