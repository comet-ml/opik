import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { ExperimentsCompare } from "@/types/datasets";

type UseCompareExperimentsListParams = {
  workspaceName: string;
  datasetId: string;
  experimentsIds: string[];
  search?: string;
  page: number;
  size: number;
};

export type UseCompareExperimentsListResponse = {
  content: ExperimentsCompare[];
  total: number;
};

const getCompareExperimentsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    datasetId,
    experimentsIds,
    search,
    size,
    page,
  }: UseCompareExperimentsListParams,
) => {
  const { data } = await api.get(
    `${DATASETS_REST_ENDPOINT}${datasetId}/items/experiments/items`,
    {
      signal,
      params: {
        workspace_name: workspaceName,
        experiment_ids: JSON.stringify(experimentsIds),
        ...(search && { name: search }),
        size,
        page,
      },
    },
  );

  return data;
};

export default function useCompareExperimentsList(
  params: UseCompareExperimentsListParams,
  options?: QueryConfig<UseCompareExperimentsListResponse>,
) {
  return useQuery({
    queryKey: ["compare-experiments", params],
    queryFn: (context) => getCompareExperimentsList(context, params),
    ...options,
  });
}
