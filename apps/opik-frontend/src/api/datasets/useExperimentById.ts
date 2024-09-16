import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment } from "@/types/datasets";

export const getExperimentById = async (
  { signal }: QueryFunctionContext,
  { experimentId }: UseExperimentByIdParams,
) => {
  const { data } = await api.get(EXPERIMENTS_REST_ENDPOINT + experimentId, {
    signal,
  });

  return data;
};

export type UseExperimentByIdParams = {
  experimentId: string;
};

export default function useExperimentById(
  params: UseExperimentByIdParams,
  options?: QueryConfig<Experiment>,
) {
  return useQuery({
    queryKey: ["experiment", params],
    queryFn: (context) => getExperimentById(context, params),
    ...options,
  });
}
