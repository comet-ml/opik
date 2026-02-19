import {
  QueryFunctionContext,
  useQuery,
  useQueryClient,
} from "@tanstack/react-query";
import { useCallback } from "react";
import api, { PROMPTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { PromptVersion } from "@/types/prompts";

type UsePromptVersionByIdParams = {
  versionId: string;
};

const getPromptVersionById = async (
  { signal }: QueryFunctionContext,
  { versionId }: UsePromptVersionByIdParams,
) => {
  const { data } = await api.get(
    `${PROMPTS_REST_ENDPOINT}versions/${versionId}`,
    {
      signal,
    },
  );

  return data;
};

export default function usePromptVersionById(
  params: UsePromptVersionByIdParams,
  options?: QueryConfig<PromptVersion>,
) {
  return useQuery({
    queryKey: ["prompt-version", params],
    queryFn: (context) => getPromptVersionById(context, params),
    ...options,
  });
}

export function useFetchPromptVersion() {
  const queryClient = useQueryClient();

  return useCallback(
    async (params: UsePromptVersionByIdParams): Promise<PromptVersion> => {
      return queryClient.fetchQuery({
        queryKey: ["prompt-version", params],
        queryFn: (context) => getPromptVersionById(context, params),
        staleTime: 5 * 60 * 1000,
      });
    },
    [queryClient],
  );
}
