import { CHART_TYPE } from "@/constants/chart";
import {
  EXPERIMENT_DATA_SOURCE,
  ExperimentsFeedbackScoresWidgetType,
} from "@/types/dashboard";
import { Groups } from "@/types/groups";
import { COLUMN_DATASET_ID, COLUMN_METADATA_ID } from "@/types/shared";

const DEFAULT_TITLE = "Experiment metrics";

const GROUP_FIELD_LABELS: Record<string, string> = {
  [COLUMN_DATASET_ID]: "Dataset",
  [COLUMN_METADATA_ID]: "Configuration",
};

const getGroupByLabel = (groups: Groups | undefined): string | null => {
  const groupsLength = groups?.length || 0;
  if (groupsLength === 0) return null;

  if (groupsLength === 1 && groups?.[0]?.field) {
    return GROUP_FIELD_LABELS[groups[0].field] || groups[0].field;
  }

  return `${groupsLength} fields`;
};

const getMetricsLabel = (feedbackScores: string[] | undefined): string => {
  if (!feedbackScores || feedbackScores.length === 0) {
    return "metrics";
  }

  if (feedbackScores.length === 1) {
    return feedbackScores[0];
  }

  return `${feedbackScores.length} metrics`;
};

const buildSelectedExperimentsTitle = (
  experimentIds: string[] | undefined,
  feedbackScores: string[] | undefined,
): string => {
  const count = experimentIds?.length || 0;

  if (count === 0) {
    return DEFAULT_TITLE;
  }

  const metricsLabel = getMetricsLabel(feedbackScores);
  const experimentsLabel = count === 1 ? "Experiment" : `${count} experiments`;

  return `${experimentsLabel} - ${metricsLabel}`;
};

const buildFilterAndGroupTitle = (
  hasFilters: boolean,
  hasGroups: boolean,
  groups: Groups | undefined,
  feedbackScores: string[] | undefined,
): string => {
  const groupByLabel = getGroupByLabel(groups);
  const metricsLabel = getMetricsLabel(feedbackScores);

  // If no metrics are selected, always return the default title
  if (metricsLabel === "metrics") {
    return DEFAULT_TITLE;
  }

  let baseLabel: string;
  if (hasFilters) {
    baseLabel = `Filtered ${metricsLabel.toLowerCase()}`;
    if (feedbackScores && feedbackScores.length === 1) {
      baseLabel = `Filtered ${metricsLabel}`;
    }
  } else if (feedbackScores && feedbackScores.length === 1) {
    baseLabel = metricsLabel;
  } else {
    baseLabel = metricsLabel;
  }

  if (hasGroups && groupByLabel) {
    return `${baseLabel} grouped by ${groupByLabel}`;
  }

  return baseLabel;
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
    groups,
    feedbackScores,
  );
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    dataSource: EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
    filters: [],
    groups: [],
    chartType: CHART_TYPE.line,
  }),
  calculateTitle: calculateExperimentsFeedbackScoresTitle,
};
