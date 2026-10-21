import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { DatasetVersion } from "@/types/datasets";

type UseDatasetVersionByHashParams = {
  datasetId: string;
  versionHash: string;
};

const getDatasetVersionByHash = async (
  { signal }: QueryFunctionContext,
  { datasetId, versionHash }: UseDatasetVersionByHashParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/versions/hash/${versionHash}`,
    { signal },
  );

  return data;
};

export default function useDatasetVersionByHash(
  params: UseDatasetVersionByHashParams,
  options?: QueryConfig<DatasetVersion>,
) {
  return useQuery({
    queryKey: ["dataset-versions", params],
    queryFn: (context) => getDatasetVersionByHash(context, params),
    ...options,
  });
}
