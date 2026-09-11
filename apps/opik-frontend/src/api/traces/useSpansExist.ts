import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { QueryConfig, SPANS_KEY, SPANS_REST_ENDPOINT } from "@/api/api";
import { LOGS_SOURCE } from "@/types/traces";

type UseSpansExistParams = {
  projectId: string;
  source?: LOGS_SOURCE;
};

export type UseSpansExistResponse = {
  exists: boolean;
};

const getSpansExist = async (
  { signal }: QueryFunctionContext,
  { projectId, source }: UseSpansExistParams,
) => {
  const { data } = await api.get<UseSpansExistResponse>(
    `${SPANS_REST_ENDPOINT}exists`,
    {
      signal,
      params: { project_id: projectId, ...(source && { source }) },
    },
  );

  return data;
};

export default function useSpansExist(
  params: UseSpansExistParams,
  options?: QueryConfig<UseSpansExistResponse>,
) {
  return useQuery({
    queryKey: [SPANS_KEY, params, "exists"],
    queryFn: (context) => getSpansExist(context, params),
    ...options,
  });
}
