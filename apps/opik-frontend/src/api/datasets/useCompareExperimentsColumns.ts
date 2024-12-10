import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ExperimentOutputColumn } from "@/types/datasets";

type UseCompareExperimentsColumnsParams = {
  datasetId: string;
  experimentsIds: string[];
};

type UseCompareExperimentsColumnsResponse = {
  columns: ExperimentOutputColumn[];
};

const getCompareExperimentsColumns = async (
  { signal }: QueryFunctionContext,
  { datasetId, experimentsIds }: UseCompareExperimentsColumnsParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items/experiments/items/output/columns`,
    {
      signal,
      params: {
        experiment_ids: JSON.stringify(experimentsIds),
      },
    },
  );

  return data;
};

export default function useCompareExperimentsColumns(
  params: UseCompareExperimentsColumnsParams,
  options?: QueryConfig<UseCompareExperimentsColumnsResponse>,
) {
  return useQuery({
    queryKey: ["compare-experiments-columns", params],
    queryFn: (context) => getCompareExperimentsColumns(context, params),
    ...options,
  });
}
