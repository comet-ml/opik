import { useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset } from "@/types/datasets";

type UseDatasetByNameParams = {
  datasetName: string;
};

const getDatasetByName = async ({ datasetName }: UseDatasetByNameParams) => {
  const response = await api.post<Dataset>(
    `${DATASETS_REST_ENDPOINT}/retrieve`,
    {
      dataset_name: datasetName,
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
    queryFn: () => getDatasetByName(params),
    ...options,
  });
}
