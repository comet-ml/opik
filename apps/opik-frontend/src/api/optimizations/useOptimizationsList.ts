import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, {
  OPTIMIZATIONS_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Optimization } from "@/types/optimizations";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";

export type UseOptimizationsListParams = {
  workspaceName: string;
  datasetId?: string;
  datasetDeleted?: boolean;
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
      ...processFilters(filters),
      ...(search && { name: search }),
      ...(datasetId && { dataset_id: datasetId }),
      size,
      page,
    },
  });

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
