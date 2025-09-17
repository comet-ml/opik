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
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items`,
    {
      signal,
      params: {
        size,
        page,
        truncate,
        // Only send free text search as filters parameter
        ...(search && { filters: search }),
      },
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
