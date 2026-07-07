import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";

type UseTracesExistParams = {
  projectId: string;
  threadOnly?: boolean;
};

export type UseTracesExistResponse = {
  exists: boolean;
};

const getTracesExist = async (
  { signal }: QueryFunctionContext,
  { projectId, threadOnly }: UseTracesExistParams,
) => {
  const { data } = await api.get<UseTracesExistResponse>(
    `${TRACES_REST_ENDPOINT}exists`,
    {
      signal,
      params: {
        project_id: projectId,
        ...(threadOnly && { thread_only: true }),
      },
    },
  );

  return data;
};

export default function useTracesExist(
  params: UseTracesExistParams,
  options?: QueryConfig<UseTracesExistResponse>,
) {
  return useQuery({
    queryKey: [TRACES_KEY, params, "exists"],
    queryFn: (context) => getTracesExist(context, params),
    ...options,
  });
}
