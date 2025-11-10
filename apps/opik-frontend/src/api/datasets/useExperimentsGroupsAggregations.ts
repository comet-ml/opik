import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import {
  EXPERIMENT_TYPE,
  ExperimentsGroupNodeWithAggregations,
} from "@/types/datasets";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { generatePromptFilters, processFilters } from "@/lib/filters";
import { processGroups } from "@/lib/groups";

const DEFAULT_EXPERIMENTS_TYPES = [EXPERIMENT_TYPE.REGULAR];

export type UseExperimentsGroupsParams = {
  workspaceName?: string;
  promptId?: string;
  types?: EXPERIMENT_TYPE[];
  filters?: Filters;
  groups: Groups;
  search?: string;
};

export type UseExperimentsGroupsResponse = {
  content: Record<string, ExperimentsGroupNodeWithAggregations>;
};

export const getExperimentsGroupsAggregations = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    promptId,
    types = DEFAULT_EXPERIMENTS_TYPES,
    filters,
    groups,
    search,
  }: UseExperimentsGroupsParams,
) => {
  const { data } = await api.get(
    `${EXPERIMENTS_REST_ENDPOINT}groups/aggregations`,
    {
      signal,
      params: {
        ...(workspaceName && { workspace_name: workspaceName }),
        ...processFilters(filters, generatePromptFilters(promptId)),
        ...processGroups(groups),
        ...(search && { name: search }),
        ...(types && { types: JSON.stringify(types) }),
      },
    },
  );

  return data;
};

export default function useExperimentsGroupsAggregations(
  params: UseExperimentsGroupsParams,
  options?: QueryConfig<UseExperimentsGroupsResponse>,
) {
  return useQuery({
    queryKey: ["experiments", { __hook: "groups-aggregations", ...params }],
    queryFn: (context) => getExperimentsGroupsAggregations(context, params),
    ...options,
  });
}
