import { QueryFunctionContext, useQuery } from "@tanstack/react-query";
import isBoolean from "lodash/isBoolean";
import api, { EXPERIMENTS_REST_ENDPOINT, QueryConfig } from "@/api/api";
import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";
import { AggregatedFeedbackScore } from "@/types/shared";
import { Sorting } from "@/types/sorting";
import { processSorting } from "@/lib/sorting";
import { Filters } from "@/types/filters";
import { generatePromptFilters, processFilters } from "@/lib/filters";

const DEFAULT_EXPERIMENTS_TYPES = [EXPERIMENT_TYPE.REGULAR];

const mergePreComputedMetrics = (experiment: Experiment): Experiment => {
  // Always update existing feedback_scores to include type in name
  const existingScoresWithType = (experiment.feedback_scores || []).map(
    (score) => ({
      ...score,
      name: score.type ? `${score.name} (${score.type})` : score.name,
    }),
  );

  // If no pre_computed_metric_aggregates, just return with updated names
  if (!experiment.pre_computed_metric_aggregates) {
    return {
      ...experiment,
      feedback_scores: existingScoresWithType,
    };
  }

  const preComputedScores: AggregatedFeedbackScore[] = [];

  Object.entries(experiment.pre_computed_metric_aggregates).forEach(
    ([feedbackScoreName, metrics]) => {
      Object.entries(metrics).forEach(([metricType, metricValue]) => {
        preComputedScores.push({
          name: `${feedbackScoreName} (${metricType})`,
          value: metricValue,
          type: metricType,
        });
      });
    },
  );

  return {
    ...experiment,
    feedback_scores: [...existingScoresWithType, ...preComputedScores],
  };
};

export type UseExperimentsListParams = {
  workspaceName?: string;
  promptId?: string;
  optimizationId?: string;
  datasetDeleted?: boolean;
  types?: EXPERIMENT_TYPE[];
  filters?: Filters;
  sorting?: Sorting;
  search?: string;
  page: number;
  size: number;
};

export type UseExperimentsListResponse = {
  content: Experiment[];
  sortable_by: string[];
  total: number;
};

export const getExperimentsList = async (
  { signal }: QueryFunctionContext,
  {
    workspaceName,
    promptId,
    optimizationId,
    datasetDeleted,
    types = DEFAULT_EXPERIMENTS_TYPES,
    filters,
    sorting,
    search,
    size,
    page,
  }: UseExperimentsListParams,
) => {
  const { data } = await api.get(EXPERIMENTS_REST_ENDPOINT, {
    signal,
    params: {
      ...(workspaceName && { workspace_name: workspaceName }),
      ...(isBoolean(datasetDeleted) && { dataset_deleted: datasetDeleted }),
      ...processFilters(filters, generatePromptFilters(promptId)),
      ...processSorting(sorting),
      ...(search && { name: search }),
      ...(optimizationId && { optimization_id: optimizationId }),
      ...(types && { types: JSON.stringify(types) }),
      size,
      page,
    },
  });

  return {
    ...data,
    content: data.content.map(mergePreComputedMetrics),
  };
};

export default function useExperimentsList(
  params: UseExperimentsListParams,
  options?: QueryConfig<UseExperimentsListResponse>,
) {
  return useQuery({
    queryKey: ["experiments", params],
    queryFn: (context) => getExperimentsList(context, params),
    ...options,
  });
}
