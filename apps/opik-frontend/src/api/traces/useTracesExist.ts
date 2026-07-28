import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, TRACES_KEY, TRACES_REST_ENDPOINT } from "@/api/api";
import { LOGS_SOURCE } from "@/types/traces";

type UseTracesExistParams = {
  projectId: string;
  threadOnly?: boolean;
  source?: LOGS_SOURCE;
};

export type UseTracesExistResponse = {
  exists: boolean;
};

const getTracesExist = async (
  { signal }: QueryFunctionContext,
  { projectId, threadOnly, source }: UseTracesExistParams,
) => {
  const { data } = await api.get<UseTracesExistResponse>(
    `${TRACES_REST_ENDPOINT}exists`,
    {
      signal,
      params: {
        project_id: projectId,
        ...(threadOnly && { thread_only: true }),
        ...(source && { source }),
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
