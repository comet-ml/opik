import { CHART_TYPE } from "@/constants/chart";
import {
  EXPERIMENT_DATA_SOURCE,
  ExperimentsFeedbackScoresWidgetType,
} from "@/types/dashboard";

const DEFAULT_TITLE = "Experiment metrics";
const EXPERIMENTS_BASE_LABEL = "Experiments";
const TITLE_SEPARATOR = " - ";
const TITLE_PART_SEPARATOR = " ";

const buildSelectedExperimentsTitle = (
  experimentIds: string[] | undefined,
  feedbackScores: string[] | undefined,
): string => {
  const count = experimentIds?.length || 0;
  const hasFeedbackScores = feedbackScores && feedbackScores.length > 0;

  if (count === 0) {
    return DEFAULT_TITLE;
  }

  if (count === 1) {
    if (hasFeedbackScores && feedbackScores.length === 1) {
      return `Experiment ${feedbackScores[0]}`;
    }
    return DEFAULT_TITLE;
  }

  const parts: string[] = [`${count} experiments`];

  if (hasFeedbackScores && feedbackScores.length === 1) {
    parts.push(feedbackScores[0]);
  } else if (hasFeedbackScores) {
    parts.push(`${feedbackScores.length} metrics`);
  }

  return parts.join(TITLE_SEPARATOR);
};

const buildFilterAndGroupTitle = (
  hasFilters: boolean,
  hasGroups: boolean,
  groupsLength: number,
  feedbackScores: string[] | undefined,
): string => {
  const parts: string[] = [EXPERIMENTS_BASE_LABEL];

  if (hasFilters && hasGroups) {
    parts.push("filtered & grouped");
  } else if (hasFilters) {
    parts.push("filtered");
  } else if (hasGroups) {
    const fieldLabel = groupsLength > 1 ? "fields" : "field";
    parts.push(`grouped by ${groupsLength} ${fieldLabel}`);
  }

  const hasFeedbackScores = feedbackScores && feedbackScores.length > 0;

  if (hasFeedbackScores) {
    if (feedbackScores.length === 1) {
      parts.push(feedbackScores[0]);
    } else {
      parts.push(`${feedbackScores.length} metrics`);
    }
  } else {
    parts.push("metrics");
  }

  return parts.join(TITLE_PART_SEPARATOR);
};

const calculateExperimentsFeedbackScoresTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as ExperimentsFeedbackScoresWidgetType["config"];

  const dataSource = widgetConfig.dataSource;
  const experimentIds = widgetConfig.experimentIds;
  const filters = widgetConfig.filters;
  const groups = widgetConfig.groups;
  const feedbackScores = widgetConfig.feedbackScores;

  const hasFilters = filters && filters.length > 0;
  const hasGroups = groups && groups.length > 0;

  if (dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS) {
    return buildSelectedExperimentsTitle(experimentIds, feedbackScores);
  }

  return buildFilterAndGroupTitle(
    Boolean(hasFilters),
    Boolean(hasGroups),
    groups?.length || 0,
    feedbackScores,
  );
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    filters: [],
    groups: [],
    chartType: CHART_TYPE.line,
  }),
  calculateTitle: calculateExperimentsFeedbackScoresTitle,
};
