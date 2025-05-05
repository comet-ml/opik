import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, {
  OPTIMIZATIONS_KEY,
  OPTIMIZATIONS_REST_ENDPOINT,
  QueryConfig,
} from "@/api/api";
import { Optimization } from "@/types/optimizations";

export type UseOptimizationsListParams = {
  workspaceName: string;
  datasetId?: string;
  datasetDeleted?: boolean;
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
    search,
    size,
    page,
  }: UseOptimizationsListParams,
) => {
  const { data } = await api.get(OPTIMIZATIONS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName, // we just need it to reset the cash in case workspace is changed
      ...(isBoolean(datasetDeleted) && { dataset_deleted: datasetDeleted }),
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
