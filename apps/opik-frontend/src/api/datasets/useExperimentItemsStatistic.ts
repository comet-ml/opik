import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ColumnsStatistic } from "@/types/shared";
import { Filters } from "@/types/filters";
import { processFilters } from "@/lib/filters";

type UseExperimentItemsStatisticParams = {
  datasetId: string;
  experimentsIds: string[];
  filters?: Filters;
};

export type UseExperimentItemsStatisticResponse = {
  stats: ColumnsStatistic;
};

const getExperimentItemsStatistic = async (
  { signal }: QueryFunctionContext,
  { datasetId, experimentsIds, filters }: UseExperimentItemsStatisticParams,
) => {
  const { data } = await api.get<UseExperimentItemsStatisticResponse>(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items/experiments/items/stats`,
    {
      signal,
      params: {
        experiment_ids: JSON.stringify(experimentsIds),
        ...processFilters(filters),
      },
    },
  );

  return data;
};

export default function useExperimentItemsStatistic(
  params: UseExperimentItemsStatisticParams,
  options?: QueryConfig<UseExperimentItemsStatisticResponse>,
) {
  return useQuery({
    queryKey: ["experiment-items-statistic", params],
    queryFn: (context) => getExperimentItemsStatistic(context, params),
    ...options,
  });
}
