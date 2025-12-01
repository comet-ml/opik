import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import isEqual from "lodash/isEqual";
import api, {
  OPTIMIZATIONS_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Optimization, OPTIMIZATION_STATUS } from "@/types/optimizations";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";
import { ACTIVE_OPTIMIZATION_FILTER } from "@/lib/optimizations";

export type UseOptimizationsListParams = {
  workspaceName: string;
  datasetId?: string;
  datasetDeleted?: boolean;
  studioOnly?: boolean;
  filters?: Filters;
  search?: string;
  page: number;
  size: number;
};

export type UseOptimizationsListResponse = {
  content: Optimization[];
  sortable_by: string[];
  total: number;
};

export const getOptimizationsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    datasetId,
    datasetDeleted,
    studioOnly,
    filters,
    search,
    size,
    page,
  }: UseOptimizationsListParams,
) => {
  const { data } = await api.get(OPTIMIZATIONS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(isBoolean(datasetDeleted) && { dataset_deleted: datasetDeleted }),
      ...(isBoolean(studioOnly) && { studio_only: studioOnly }),
      ...processFilters(filters),
      ...(search && { name: search }),
      ...(datasetId && { dataset_id: datasetId }),
      size,
      page,
    },
  });

  // ALEX
  // Filter out optimizations that are not running or initialized when ACTIVE_OPTIMIZATION_FILTER is used
  if (filters && isEqual(filters, ACTIVE_OPTIMIZATION_FILTER)) {
    return {
      ...data,
      content: data.content.filter(
        (opt: Optimization) =>
          opt.status === OPTIMIZATION_STATUS.RUNNING ||
          opt.status === OPTIMIZATION_STATUS.INITIALIZED,
      ),
    };
  }

  return data;
};

export default function useOptimizationsList(
  params: UseOptimizationsListParams,
  options?: QueryConfig<UseOptimizationsListResponse>,
) {
  return useQuery({
    queryKey: [OPTIMIZATIONS_KEY, params],
    queryFn: (context) => getOptimizationsList(context, params),
    ...options,
  });
}
