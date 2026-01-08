import { ExperimentLeaderboardWidgetType } from "@/types/dashboard";

const DEFAULT_TITLE = "Experiment leaderboard";

/**
 * Generate dynamic title based on widget configuration
 */
const calculateExperimentLeaderboardTitle = (
  config: Record<string, unknown>,
): string => {
  const widgetConfig = config as ExperimentLeaderboardWidgetType["config"];

  const primaryMetric = widgetConfig.primaryMetric;
  const selectedMetrics = widgetConfig.selectedMetrics;
  const maxRows = widgetConfig.maxRows;

  // If no configuration, return default
  if (!primaryMetric && (!selectedMetrics || selectedMetrics.length === 0)) {
    return DEFAULT_TITLE;
  }

  // Build title with primary metric
  if (primaryMetric) {
    const metricLabel = formatMetricName(primaryMetric);
    const suffix = maxRows && maxRows < 20 ? ` (Top ${maxRows})` : "";
    return `${metricLabel} leaderboard${suffix}`;
  }

  // Build title with selected metrics count
  if (selectedMetrics && selectedMetrics.length > 0) {
    const metricsLabel =
      selectedMetrics.length === 1
        ? formatMetricName(selectedMetrics[0])
        : `${selectedMetrics.length} metrics`;
    return `Leaderboard - ${metricsLabel}`;
  }

  return DEFAULT_TITLE;
};

/**
 * Format metric name for display
 */
function formatMetricName(metricName: string): string {
  // Handle special system metrics
  const systemMetrics: Record<string, string> = {
    duration: "Duration",
    cost: "Cost",
    trace_count: "Trace count",
  };

  if (systemMetrics[metricName]) {
    return systemMetrics[metricName];
  }

  // Format feedback score names (e.g., "hallucination_rate" -> "Hallucination rate")
  return metricName
    .split("_")
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

export const widgetHelpers = {
  /**
   * Get default configuration for the leaderboard widget
   */
  getDefaultConfig: () => ({
    filters: [],
    selectedMetrics: [], // Empty = show all
    primaryMetric: "accuracy", // Default ranking metric
    sortOrder: "desc", // desc = highest first for scores
    showRank: true,
    maxRows: 20,
    displayColumns: ["name", "dataset", "duration", "cost", "trace_count"],
    metadataColumns: [], // User-defined metadata fields (e.g., ["provider", "model_name"])
    columnsOrder: [], // Empty = default order
  }),

  /**
   * Calculate dynamic title based on configuration
   */
  calculateTitle: calculateExperimentLeaderboardTitle,
};

