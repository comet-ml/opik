import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { DATASETS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Dataset } from "@/types/datasets";

type UseDatasetsListParams = {
  workspaceName: string;
  withExperimentsOnly?: boolean;
  search?: string;
  page: number;
  size: number;
};

export type UseDatasetsListResponse = {
  content: Dataset[];
  total: number;
};

const getDatasetsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    withExperimentsOnly,
    search,
    size,
    page,
  }: UseDatasetsListParams,
) => {
  const { data } = await api.get(DATASETS_REST_ENDPOINT, {
    signal,
    params: {
      workspace_name: workspaceName,
      ...(isBoolean(withExperimentsOnly) && {
        with_experiments_only: withExperimentsOnly,
      }),
      ...(search && { name: search }),
      size,
      page,
    },
  });

  return data;
};

export default function useDatasetsList(
  params: UseDatasetsListParams,
  options?: QueryConfig<UseDatasetsListResponse>,
) {
  return useQuery({
    queryKey: ["datasets", params],
    queryFn: (context) => getDatasetsList(context, params),
    ...options,
  });
}
