import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset } from "@/types/datasets";

type UseDatasetByNameParams = {
  datasetName: string;
};

const getDatasetByName = async (
  { signal }: QueryFunctionContext,
  { datasetName }: UseDatasetByNameParams,
) => {
  const response = await api.post<Dataset>(
    `${DATASETS_REST_ENDPOINT}retrieve`,
    {
      dataset_name: datasetName,
    },
    {
      signal,
    },
  );

  return response.data;
};

export default function useDatasetItemByName(
  params: UseDatasetByNameParams,
  options?: QueryConfig<Dataset>,
) {
  return useQuery({
    queryKey: ["dataset", params],
    queryFn: (context) => getDatasetByName(context, params),
    ...options,
  });
}
