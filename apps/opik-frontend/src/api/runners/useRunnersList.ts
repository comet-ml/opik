import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { RUNNERS_KEY, RUNNERS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Runner } from "@/types/runners";

type UseRunnersListParams = {
  workspaceName?: string;
};

const getRunnersList = async ({ signal }: QueryFunctionContext) => {
  const { data } = await api.get<Runner[]>(RUNNERS_REST_ENDPOINT, {
    signal,
  });

  return data;
};

export default function useRunnersList(
  params: UseRunnersListParams,
  options?: QueryConfig<Runner[]>,
) {
  return useQuery({
    queryKey: [RUNNERS_KEY, params],
    queryFn: (context) => getRunnersList(context),
    ...options,
  });
}
