import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseDatasetsListParams = {
  workspaceName: string;
  withExperimentsOnly?: boolean;
  withOptimizationsOnly?: boolean;
  promptId?: string;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

export type UseDatasetsListResponse = {
  content: Dataset[];
  sortable_by: string[];
  total: number;
};

const getDatasetsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    withExperimentsOnly,
    withOptimizationsOnly,
    promptId,
    filters,
    sorting,
    search,
    size,
    page,
  }: UseDatasetsListParams,
) => {
  const { data } = await api.get(DATASETS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(withExperimentsOnly && {
        with_experiments_only: withExperimentsOnly,
      }),
      ...(withOptimizationsOnly && {
        with_optimizations_only: withOptimizationsOnly,
      }),
      ...processFilters(filters),
      ...processSorting(sorting),
      ...(search && { name: search }),
      ...(promptId && { prompt_id: promptId }),
      size,
      page,
    },
  });

  return data;
};

export default function useDatasetsList(
  params: UseDatasetsListParams,
  options?: QueryConfig<UseDatasetsListResponse>,
) {
  return useQuery({
    queryKey: ["datasets", params],
    queryFn: (context) => getDatasetsList(context, params),
    ...options,
  });
}
