import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import {
  EXPERIMENT_TYPE,
  Experiment,
  ExperimentsGroupNodeWithAggregations,
  ExperimentsAggregations,
} from "@/types/datasets";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { processFilters } from "@/lib/filters";
import { UseExperimentsListResponse } from "./useExperimentsList";
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
  content: Record<string, ExperimentsGroupNodeWithAggregations>;
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

const calculateAggregations = (
  experiments: Experiment[],
): ExperimentsAggregations => {
  if (experiments.length === 0) {
    return {
      experiment_count: 0,
      trace_count: 0,
      total_estimated_cost: undefined,
      total_estimated_cost_avg: undefined,
      duration: undefined,
      feedback_scores: undefined,
    };
  }

  // Calculate basic counts
  const experiment_count = experiments.length;
  const trace_count = experiments.reduce(
    (sum, exp) => sum + (exp.trace_count || 0),
    0,
  );

  // Calculate cost aggregations
  const experimentsWithCost = experiments.filter(
    (exp) =>
      exp.total_estimated_cost !== null &&
      exp.total_estimated_cost !== undefined,
  );

  const total_estimated_cost =
    experimentsWithCost.length > 0
      ? experimentsWithCost.reduce(
          (sum, exp) => sum + (exp.total_estimated_cost || 0),
          0,
        )
      : undefined;

  const total_estimated_cost_avg =
    experimentsWithCost.length > 0
      ? total_estimated_cost! / experimentsWithCost.length
      : undefined;

  // Calculate duration aggregations
  const experimentsWithDuration = experiments.filter(
    (exp) => exp.duration?.p50 !== null && exp.duration?.p50 !== undefined,
  );

  const duration =
    experimentsWithDuration.length > 0
      ? {
          p50:
            experimentsWithDuration.reduce(
              (sum, exp) => sum + (exp.duration?.p50 || 0),
              0,
            ) / experimentsWithDuration.length,
          p90:
            experimentsWithDuration.reduce(
              (sum, exp) => sum + (exp.duration?.p90 || 0),
              0,
            ) / experimentsWithDuration.length,
          p99:
            experimentsWithDuration.reduce(
              (sum, exp) => sum + (exp.duration?.p99 || 0),
              0,
            ) / experimentsWithDuration.length,
        }
      : undefined;

  // Calculate feedback scores aggregations
  const feedbackScoresMap = new Map<string, number[]>();

  experiments.forEach((exp) => {
    exp.feedback_scores?.forEach((score) => {
      if (!feedbackScoresMap.has(score.name)) {
        feedbackScoresMap.set(score.name, []);
      }
      feedbackScoresMap.get(score.name)!.push(score.value);
    });
  });

  const feedback_scores = Array.from(feedbackScoresMap.entries()).map(
    ([name, values]) => ({
      name,
      value: values.reduce((sum, val) => sum + val, 0) / values.length,
    }),
  );

  return {
    experiment_count,
    trace_count,
    total_estimated_cost,
    total_estimated_cost_avg,
    duration,
    feedback_scores: feedback_scores.length > 0 ? feedback_scores : undefined,
  };
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
  ): Record<string, ExperimentsGroupNodeWithAggregations> => {
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

    const groupNodes: Record<string, ExperimentsGroupNodeWithAggregations> = {};

    // Create nodes for each group value
    Object.entries(groupedByField).forEach(
      ([groupValue, experimentsInGroup]) => {
        const isLastGroup = groupIndex === groups.length - 1;
        const aggregations = calculateAggregations(experimentsInGroup);

        if (isLastGroup) {
          // Last level - create leaf node with aggregations
          groupNodes[groupValue] = {
            aggregations,
          };
        } else {
          // Not last level - create node with nested groups and aggregations
          const nestedGroups = groupExperiments(
            experimentsInGroup,
            groupIndex + 1,
            [...currentPath, groupValue],
          );

          groupNodes[groupValue] = {
            aggregations,
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

export const getExperimentsGroupsAggregations = async (
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
