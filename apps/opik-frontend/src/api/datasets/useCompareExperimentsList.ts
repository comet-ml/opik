import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetItemColumn, ExperimentsCompare } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseCompareExperimentsListParams = {
  workspaceName: string;
  datasetId: string;
  experimentsIds: string[];
  search?: string;
  filters?: Filters;
  truncate?: boolean;
  page: number;
  size: number;
};

export type UseCompareExperimentsListResponse = {
  content: ExperimentsCompare[];
  columns: DatasetItemColumn[];
  total: number;
};

const getCompareExperimentsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    datasetId,
    experimentsIds,
    search,
    filters,
    truncate = false,
    size,
    page,
  }: UseCompareExperimentsListParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items/experiments/items`,
    {
      signal,
      params: {
        workspace_name: workspaceName,
        experiment_ids: JSON.stringify(experimentsIds),
        ...processFilters(filters),
        ...(search && { name: search }),
        truncate,
        size,
        page,
      },
    },
  );

  return data;
};

export default function useCompareExperimentsList(
  params: UseCompareExperimentsListParams,
  options?: QueryConfig<UseCompareExperimentsListResponse>,
) {
  return useQuery({
    queryKey: ["compare-experiments", params],
    queryFn: (context) => getCompareExperimentsList(context, params),
    ...options,
  });
}
