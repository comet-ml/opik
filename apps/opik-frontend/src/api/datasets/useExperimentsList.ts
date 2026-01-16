import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filters } from "@/types/filters";
import { generatePromptFilters, processFilters } from "@/lib/filters";

const DEFAULT_EXPERIMENTS_TYPES = [EXPERIMENT_TYPE.REGULAR];

export type UseExperimentsListParams = {
  workspaceName?: string;
  promptId?: string;
  optimizationId?: string;
  datasetDeleted?: boolean;
  types?: EXPERIMENT_TYPE[];
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
  queryKey?: string;
  experimentIds?: string[];
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
    promptId,
    optimizationId,
    datasetDeleted,
    types = DEFAULT_EXPERIMENTS_TYPES,
    filters,
    sorting,
    search,
    size,
    page,
    experimentIds,
  }: UseExperimentsListParams,
) => {
  const { data } = await api.get(EXPERIMENTS_REST_ENDPOINT, {
    signal,
    params: {
      ...(workspaceName && { workspace_name: workspaceName }),
      ...(isBoolean(datasetDeleted) && { dataset_deleted: datasetDeleted }),
      ...processFilters(filters, generatePromptFilters(promptId)),
      ...processSorting(sorting),
      ...(search && { name: search }),
      ...(optimizationId && { optimization_id: optimizationId }),
      ...(types && { types: JSON.stringify(types) }),
      ...(experimentIds && { experiment_ids: JSON.stringify(experimentIds) }),
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
    queryKey: [params.queryKey ?? "experiments", params],
    queryFn: (context) => getExperimentsList(context, params),
    ...options,
  });
}
