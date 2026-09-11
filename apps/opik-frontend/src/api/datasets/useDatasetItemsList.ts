import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { generateSearchByFieldFilters, processFilters } from "@/lib/filters";

export type UseDatasetItemsListParams = {
  datasetId: string;
  filters?: Filters;
  page: number;
  size: number;
  search?: string;
  truncate?: boolean;
  versionId?: string;
};

export type UseDatasetItemsListResponse = {
  content: DatasetItem[];
  columns: DatasetItemColumn[];
  total: number;
  has_draft?: boolean;
};

const getDatasetItemsList = async (
  { signal }: QueryFunctionContext,
  {
    datasetId,
    filters,
    size,
    page,
    search,
    truncate = false,
    versionId,
  }: UseDatasetItemsListParams,
): Promise<UseDatasetItemsListResponse> => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items`,
    {
      signal,
      params: {
        ...processFilters(
          filters,
          generateSearchByFieldFilters("full_data", search),
        ),
        size,
        page,
        truncate,
        ...(versionId && { version: versionId }),
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
