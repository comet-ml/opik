import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, {
  COMPARE_EXPERIMENTS_KEY,
  DATASETS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { DatasetItemColumn, ExperimentsCompare } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { Sorting } from "@/types/sorting";
import { processFilters } from "@/lib/filters";
import { processSorting } from "@/lib/sorting";

type UseCompareExperimentsListParams = {
  workspaceName: string;
  datasetId: string;
  experimentsIds: string[];
  search?: string;
  filters?: Filters;
  sorting?: Sorting;
  truncate?: boolean;
  page: number;
  size: number;
};

export type UseCompareExperimentsListResponse = {
  content: ExperimentsCompare[];
  columns: DatasetItemColumn[];
  total: number;
  sortable_by: string[];
};

const getCompareExperimentsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    datasetId,
    experimentsIds,
    search,
    filters,
    sorting,
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
        ...processSorting(sorting),
        ...(search && { search }),
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
    queryKey: [COMPARE_EXPERIMENTS_KEY, params],
    queryFn: (context) => getCompareExperimentsList(context, params),
    ...options,
  });
}
