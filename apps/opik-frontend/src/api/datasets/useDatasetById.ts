import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset } from "@/types/datasets";

const getDatasetById = async (
  { signal }: QueryFunctionContext,
  { datasetId }: UseDatasetByIdParams,
): Promise<Dataset> => {
  const { data } = await api.get(DATASETS_REST_ENDPOINT + datasetId, {
    signal,
  });

  return data;
};

type UseDatasetByIdParams = {
  datasetId: string;
};

export default function useDatasetById(
  params: UseDatasetByIdParams,
  options?: QueryConfig<Dataset>,
) {
  return useQuery({
    queryKey: ["dataset", params],
    queryFn: (context) => getDatasetById(context, params),
    ...options,
  });
}
