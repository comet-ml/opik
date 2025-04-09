import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

export type UseExperimentsListParams = {
  workspaceName: string;
  datasetId?: string;
  promptId?: string;
  datasetDeleted?: boolean;
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
    datasetDeleted,
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
      ...(promptId && { prompt_id: promptId }),
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
