import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { PROJECTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";

type UseProjectDatasetsListParams = {
  projectId: string;
  withExperimentsOnly?: boolean;
  withOptimizationsOnly?: boolean;
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

type UseProjectDatasetsListResponse = {
  content: Dataset[];
  sortable_by: string[];
  total: number;
};

const getProjectDatasetsList = async (
  { signal }: QueryFunctionContext,
  {
    projectId,
    withExperimentsOnly,
    withOptimizationsOnly,
    filters,
    sorting,
    search,
    size,
    page,
  }: UseProjectDatasetsListParams,
) => {
  const { data } = await api.get(
    `${PROJECTS_REST_ENDPOINT}${projectId}/datasets`,
    {
      signal,
      params: {
        ...processFilters(filters),
        ...processSorting(sorting),
        ...(search && { name: search }),
        ...(withExperimentsOnly && {
          with_experiments_only: withExperimentsOnly,
        }),
        ...(withOptimizationsOnly && {
          with_optimizations_only: withOptimizationsOnly,
        }),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useProjectDatasetsList(
  params: UseProjectDatasetsListParams,
  options?: QueryConfig<UseProjectDatasetsListResponse>,
) {
  return useQuery({
    queryKey: ["project-datasets", params],
    queryFn: (context) => getProjectDatasetsList(context, params),
    ...options,
  });
}
