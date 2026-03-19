import { CHART_TYPE } from "@/constants/chart";
import { ExperimentsFeedbackScoresWidgetType } from "@/types/dashboard";
import { Groups } from "@/types/groups";
import { COLUMN_DATASET_ID, COLUMN_METADATA_ID } from "@/types/shared";
import { EXPERIMENT_IDS_FILTER_FIELD } from "@/lib/filters";
import { DEFAULT_MAX_EXPERIMENTS } from "@/lib/dashboard/utils";

const DEFAULT_TITLE = "Experiment metrics";

const GROUP_FIELD_LABELS: Record<string, string> = {
  [COLUMN_DATASET_ID]: "Evaluation suite",
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
  hasExperimentIdsFilter: boolean,
  feedbackScores: string[] | undefined,
): string => {
  if (!hasExperimentIdsFilter) {
    return DEFAULT_TITLE;
  }

  const metricsLabel = getMetricsLabel(feedbackScores);
  return `Selected experiments - ${metricsLabel}`;
};

const buildFilterAndGroupTitle = (
  hasFilters: boolean,
  hasGroups: boolean,
  groups: Groups | undefined,
  feedbackScores: string[] | undefined,
): string => {
  const groupByLabel = getGroupByLabel(groups);
  const metricsLabel = getMetricsLabel(feedbackScores);

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

  const filters = widgetConfig.filters;
  const groups = widgetConfig.groups;
  const feedbackScores = widgetConfig.feedbackScores;

  const hasExperimentIdsFilter = filters?.some(
    (f) => f.field === EXPERIMENT_IDS_FILTER_FIELD,
  );
  const hasOtherFilters = filters?.some(
    (f) => f.field !== EXPERIMENT_IDS_FILTER_FIELD,
  );
  const hasGroups = groups && groups.length > 0;

  if (hasExperimentIdsFilter && !hasOtherFilters && !hasGroups) {
    return buildSelectedExperimentsTitle(true, feedbackScores);
  }

  return buildFilterAndGroupTitle(
    Boolean(hasOtherFilters),
    Boolean(hasGroups),
    groups,
    feedbackScores,
  );
};

export const widgetHelpers = {
  getDefaultConfig: () => ({
    filters: [],
    groups: [],
    chartType: CHART_TYPE.bar,
    maxExperimentsCount: DEFAULT_MAX_EXPERIMENTS,
  }),
  calculateTitle: calculateExperimentsFeedbackScoresTitle,
};
