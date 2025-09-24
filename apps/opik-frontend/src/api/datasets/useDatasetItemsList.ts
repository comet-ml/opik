import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";

type UseDatasetItemsListParams = {
  datasetId: string;
  page: number;
  size: number;
  search?: string;
  truncate?: boolean;
};

export type UseDatasetItemsListResponse = {
  content: DatasetItem[];
  columns: DatasetItemColumn[];
  total: number;
};

const getDatasetItemsList = async (
  { signal }: QueryFunctionContext,
  {
    datasetId,
    size,
    page,
    search,
    truncate = false,
  }: UseDatasetItemsListParams,
) => {
  const params: Record<string, any> = {
    size,
    page,
    truncate,
  };

  // Add search as filters parameter if provided
  if (search) {
    params.filters = JSON.stringify([{
      field: "data",
      operator: "contains",
      value: search
    }]);
  }

  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items`,
    {
      signal,
      params,
    },
  );

  return data;
};

export default function useDatasetItemsList(
  params: UseDatasetItemsListParams,
  options?: QueryConfig<UseDatasetItemsListResponse>,
) {
  return useQuery({
    queryKey: ["dataset-items", params],
    queryFn: (context) => getDatasetItemsList(context, params),
    ...options,
  });
}
