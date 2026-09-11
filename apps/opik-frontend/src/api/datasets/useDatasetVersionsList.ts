import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetVersion } from "@/types/datasets";

type UseDatasetVersionsListParams = {
  datasetId: string;
  page: number;
  size: number;
};

export type UseDatasetVersionsListResponse = {
  content: DatasetVersion[];
  total: number;
};

const getDatasetVersionsList = async (
  { signal }: QueryFunctionContext,
  { datasetId, size, page }: UseDatasetVersionsListParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/versions`,
    {
      signal,
      params: {
        size,
        page,
      },
    },
  );

  return data;
};

export default function useDatasetVersionsList(
  params: UseDatasetVersionsListParams,
  options?: QueryConfig<UseDatasetVersionsListResponse>,
) {
  return useQuery({
    queryKey: ["dataset-versions", params],
    queryFn: (context) => getDatasetVersionsList(context, params),
    ...options,
  });
}
