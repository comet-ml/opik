import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

const DEFAULT_EXPERIMENTS_TYPES = [EXPERIMENT_TYPE.REGULAR];

export type UseExperimentsListParams = {
  workspaceName: string;
  datasetId?: string;
  promptId?: string;
  optimizationId?: string;
  datasetDeleted?: boolean;
  types?: EXPERIMENT_TYPE[];
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

export type UseExperimentsListResponse = {
  content: Experiment[];
  sortable_by: string[];
  total: number;
};

export const getExperimentsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    datasetId,
    promptId,
    optimizationId,
    datasetDeleted,
    types = DEFAULT_EXPERIMENTS_TYPES,
    sorting,
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
      ...processSorting(sorting),
      ...(search && { name: search }),
      ...(datasetId && { datasetId }),
      ...(optimizationId && { optimization_id: optimizationId }),
      ...(promptId && { prompt_id: promptId }),
      ...(types && { types: JSON.stringify(types) }),
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
