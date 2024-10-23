import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment } from "@/types/datasets";

export type UseExperimentsListParams = {
  workspaceName: string;
  datasetId?: string;
  search?: string;
  page: number;
  size: number;
};

export type UseExperimentsListResponse = {
  content: Experiment[];
  total: number;
};

export const getExperimentsList = async (
  { signal }: QueryFunctionContext,
  { workspaceName, datasetId, search, size, page }: UseExperimentsListParams,
) => {
  const { data } = await api.get(EXPERIMENTS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(search && { name: search }),
      ...(datasetId && { datasetId }),
      size,
      page,
    },
  });

  return data;
};

export default function useExperimentsList(
  params: UseExperimentsListParams,
  options?: QueryConfig<UseExperimentsListResponse>,
) {
  return useQuery({
    queryKey: ["experiments", params],
    queryFn: (context) => getExperimentsList(context, params),
    ...options,
  });
}
