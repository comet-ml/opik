import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import {
  EXPERIMENT_TYPE,
  ExperimentsGroupNode,
  Experiment,
} from "@/types/datasets";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { processFilters } from "@/lib/filters";
import { processGroups } from "@/lib/groups";
import { UseExperimentsListResponse } from "./useExperimentsList";
import { DELETED_DATASET_LABEL } from "@/constants/groups";
import get from "lodash/get";

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
  content: Record<string, ExperimentsGroupNode>;
};

const getFieldValue = (
  experiment: Experiment,
  field: string,
  key?: string,
): string => {
  // Handle nested field access (e.g., "metadata.key")
  let value: unknown = get(experiment, field, undefined);

  // If this is a dictionary type and key is provided, get the specific key
  if (key && typeof value === "object" && value !== null) {
    value = get(value, key, undefined);
  }

  // Convert to string for grouping
  if (value === null || value === undefined || typeof value === "object") {
    return "";
  }

  return String(value);
};

const buildExperimentsGroups = (
  response: UseExperimentsListResponse,
  groups: Groups,
): UseExperimentsGroupsResponse => {
  if (!groups.length) {
    // If no groups specified, return an empty structure
    return { content: {} };
  }

  const { content: experiments } = response;

  // Group experiments recursively based on the group configuration
  const groupExperiments = (
    experimentsToGroup: Experiment[],
    groupIndex: number,
    currentPath: string[] = [],
  ): Record<string, ExperimentsGroupNode> => {
    if (groupIndex >= groups.length) {
      return {};
    }

    const currentGroup = groups[groupIndex];
    const groupedByField: Record<string, Experiment[]> = {};

    // Group experiments by the current field
    experimentsToGroup.forEach((experiment) => {
      const fieldValue = getFieldValue(
        experiment,
        currentGroup.field,
        currentGroup.key,
      );

      if (!groupedByField[fieldValue]) {
        groupedByField[fieldValue] = [];
      }
      groupedByField[fieldValue].push(experiment);
    });

    const groupNodes: Record<string, ExperimentsGroupNode> = {};

    // Create nodes for each group value
    Object.entries(groupedByField).forEach(
      ([groupValue, experimentsInGroup]) => {
        const isLastGroup = groupIndex === groups.length - 1;
        const label =
          currentGroup.field === "dataset_id"
            ? experimentsInGroup[0].dataset_name ?? DELETED_DATASET_LABEL
            : undefined;

        if (isLastGroup) {
          // Last level - create leaf node
          groupNodes[groupValue] = {
            label,
          };
        } else {
          // Not last level - create node with nested groups
          const nestedGroups = groupExperiments(
            experimentsInGroup,
            groupIndex + 1,
            [...currentPath, groupValue],
          );

          groupNodes[groupValue] = {
            label,
            groups: nestedGroups,
          };
        }
      },
    );

    return groupNodes;
  };

  return {
    content: groupExperiments(experiments, 0),
  };
};

export const getExperimentsGroups = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    promptId,
    types = DEFAULT_EXPERIMENTS_TYPES,
    filters: externalFilters,
    groups,
    search,
  }: UseExperimentsGroupsParams,
) => {
  // TODO lala temporary fix for dataset_id filter
  const datasetFilter = externalFilters?.find((f) => f.field === "dataset_id");
  const datasetId = datasetFilter?.value;
  const filters =
    externalFilters?.filter((f) => f.field !== "dataset_id") || [];

  const { data } = await api.get(EXPERIMENTS_REST_ENDPOINT, {
    signal,
    params: {
      ...(workspaceName && { workspace_name: workspaceName }),
      ...processFilters(filters),
      ...(search && { name: search }),
      ...(datasetId && { datasetId }),
      ...(promptId && { prompt_id: promptId }),
      ...(types && { types: JSON.stringify(types) }),
      size: 100500,
      page: 1,
    },
  });

  return buildExperimentsGroups(data, groups);
};

export default function useExperimentsGroups(
  params: UseExperimentsGroupsParams,
  options?: QueryConfig<UseExperimentsGroupsResponse>,
) {
  return useQuery({
    queryKey: ["experiments-groups", params], // TODO lala keys
    queryFn: (context) => getExperimentsGroups(context, params),
    ...options,
  });
}
