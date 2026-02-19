import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { RUNNERS_KEY, RUNNERS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Runner } from "@/types/runners";

type UseRunnerByIdParams = {
  runnerId: string;
};

const getRunnerById = async (
  { signal }: QueryFunctionContext,
  { runnerId }: UseRunnerByIdParams,
) => {
  const { data } = await api.get<Runner>(
    `${RUNNERS_REST_ENDPOINT}${runnerId}`,
    { signal },
  );

  return data;
};

export default function useRunnerById(
  params: UseRunnerByIdParams,
  options?: QueryConfig<Runner>,
) {
  return useQuery({
    queryKey: [RUNNERS_KEY, params],
    queryFn: (context) => getRunnerById(context, params),
    ...options,
  });
}
