import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment } from "@/types/datasets";

export type UseExperimentsListParams = {
  workspaceName: string;
  datasetId?: string;
  datasetDeleted?: boolean;
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
  {
    workspaceName,
    datasetId,
    datasetDeleted,
    search,
    size,
    page,
  }: UseExperimentsListParams,
) => {
  const { data } = await api.get(EXPERIMENTS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(isBoolean(datasetDeleted) && { dataset_deleted: datasetDeleted }),
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
