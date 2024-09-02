import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetItem } from "@/types/datasets";

const getDatasetItemById = async (
  { signal }: QueryFunctionContext,
  { datasetItemId }: UseDatasetItemByIdParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}items/${datasetItemId}`,
    {
      signal,
    },
  );

  return data;
};

type UseDatasetItemByIdParams = {
  datasetItemId: string;
};

export default function useDatasetItemById(
  params: UseDatasetItemByIdParams,
  options?: QueryConfig<DatasetItem>,
) {
  return useQuery({
    queryKey: ["dataset-item", params],
    queryFn: (context) => getDatasetItemById(context, params),
    ...options,
  });
}
