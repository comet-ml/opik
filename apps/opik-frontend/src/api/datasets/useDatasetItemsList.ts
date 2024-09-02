import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetItem } from "@/types/datasets";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseDatasetItemsListParams = {
  datasetId: string;
  filters?: Filters;
  page: number;
  size: number;
};

export type UseDatasetItemsListResponse = {
  content: DatasetItem[];
  total: number;
};

const getDatasetItemsList = async (
  { signal }: QueryFunctionContext,
  { datasetId, filters, size, page }: UseDatasetItemsListParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items`,
    {
      signal,
      params: {
        ...processFilters(filters),
        size,
        page,
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
