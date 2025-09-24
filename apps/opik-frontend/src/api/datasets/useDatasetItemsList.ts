import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetItem, DatasetItemColumn } from "@/types/datasets";
import { processFilters } from "@/lib/filters";
import { uniqueId } from "lodash";
import { COLUMN_TYPE } from "@/types/shared";
import { FilterOperator } from "@/types/filters";

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
  const searchFilter = search
    ? [
        {
          id: uniqueId(),
          type: COLUMN_TYPE.string,
          field: "data",
          operator: "contains" as FilterOperator,
          value: search,
        },
      ]
    : [];

  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items`,
    {
      signal,
      params: {
        ...processFilters([...searchFilter]),
        size,
        page,
        truncate,
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
