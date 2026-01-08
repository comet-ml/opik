import React, { memo, useMemo, useCallback, useState } from "react";
import { useShallow } from "zustand/react/shallow";
import { useNavigate } from "@tanstack/react-router";
import {
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  Trophy,
  Award,
  Medal,
  Target,
  Zap,
  DollarSign,
  Clock,
  BarChart3,
  TrendingUp,
  Gauge,
  AlertTriangle,
  LayoutGrid,
  List,
} from "lucide-react";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import { useDashboardStore, selectUpdateWidget } from "@/store/DashboardStore";
import {
  DashboardWidgetComponentProps,
  ExperimentLeaderboardWidgetType,
} from "@/types/dashboard";
import { Spinner } from "@/components/ui/spinner";
import { Experiment } from "@/types/datasets";
import useAppStore from "@/store/AppStore";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import { ColumnData, COLUMN_TYPE } from "@/types/shared";
import { sortColumnsByOrder } from "@/lib/table";

// MOCK DATA - Remove when integrating with real API
import { mockLeaderboardExperiments } from "@/mocks/experimentLeaderboardMockData";

type SortDirection = "asc" | "desc" | null;

type LeaderboardRow = {
  rank: number;
  experiment: Experiment;
  metrics: Record<string, number | null>;
  metadata: Record<string, string | null>;
};

const ExperimentLeaderboardWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();

  const widget = useDashboardStore(
    useShallow((state) => {
      if (preview) {
        return state.previewWidget;
      }
      if (!sectionId || !widgetId) return null;
      const section = state.sections.find((s) => s.id === sectionId);
      return section?.widgets.find((w) => w.id === widgetId);
    }),
  );

  const onAddEditWidgetCallback = useDashboardStore(
    (state) => state.onAddEditWidgetCallback,
  );

  const handleEdit = useCallback(() => {
    if (sectionId && widgetId) {
      onAddEditWidgetCallback?.({ sectionId, widgetId });
    }
  }, [sectionId, widgetId, onAddEditWidgetCallback]);

  const widgetConfig = widget?.config as
    | ExperimentLeaderboardWidgetType["config"]
    | undefined;

  const primaryMetric = widgetConfig?.primaryMetric || "accuracy";
  const selectedMetrics = widgetConfig?.selectedMetrics || [];
  const configSortOrder = widgetConfig?.sortOrder || "desc";
  const showRank = widgetConfig?.showRank !== false;
  const maxRows = widgetConfig?.maxRows || 20;
  const displayColumns = widgetConfig?.displayColumns || [
    "name",
    "dataset",
    "duration",
    "cost",
    "trace_count",
  ];
  const metadataColumns = widgetConfig?.metadataColumns || [];
  const columnsOrder = widgetConfig?.columnsOrder || [];

  const updateWidget = useDashboardStore(selectUpdateWidget);

  // Local state for sorting
  const [sortColumn, setSortColumn] = useState<string>(primaryMetric);
  const [sortDirection, setSortDirection] = useState<SortDirection>(
    configSortOrder as SortDirection,
  );
  const [density, setDensity] = useState<"comfortable" | "compact">(
    "comfortable",
  );

  // MOCK: Simulate loading
  const isPending = false;

  // MOCK: Use mock data instead of API call
  const experiments = mockLeaderboardExperiments.slice(0, maxRows);

  // Extract all available metrics from experiments
  const allAvailableMetrics = useMemo(() => {
    const metricSet = new Set<string>();
    experiments.forEach((exp) => {
      exp.feedback_scores?.forEach((score) => metricSet.add(score.name));
      exp.experiment_scores?.forEach((score) => metricSet.add(score.name));
    });
    // Add system metrics
    metricSet.add("duration");
    metricSet.add("cost");
    metricSet.add("trace_count");
    return Array.from(metricSet);
  }, [experiments]);

  // Determine which metrics to display
  const metricsToDisplay = useMemo(() => {
    if (selectedMetrics.length === 0) {
      return allAvailableMetrics;
    }
    return selectedMetrics.filter((m) => allAvailableMetrics.includes(m));
  }, [selectedMetrics, allAvailableMetrics]);

  // Format metric name for display
  const formatMetricName = useCallback((metricName: string): string => {
    const names: Record<string, string> = {
      duration: "Duration",
      cost: "Cost",
      trace_count: "Traces",
      hallucination_rate: "Hallucination",
      mean_score: "Mean Score",
    };
    if (names[metricName]) return names[metricName];
    return metricName
      .split("_")
      .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
      .join(" ");
  }, []);

  // Get metric icon
  const getMetricIcon = useCallback((metricName: string) => {
    const iconMap: Record<
      string,
      React.ComponentType<{ className?: string }>
    > = {
      accuracy: Target,
      hallucination_rate: AlertTriangle,
      relevance: TrendingUp,
      helpfulness: Award,
      mean_score: BarChart3,
      duration: Clock,
      cost: DollarSign,
      trace_count: Gauge,
    };
    return iconMap[metricName] || BarChart3;
  }, []);

  // Determine metric category for grouping
  const getMetricCategory = useCallback(
    (metricName: string): "quality" | "efficiency" | "other" => {
      const qualityMetrics = [
        "accuracy",
        "hallucination_rate",
        "relevance",
        "helpfulness",
        "mean_score",
      ];
      const efficiencyMetrics = ["duration", "cost", "trace_count"];

      if (qualityMetrics.includes(metricName)) return "quality";
      if (efficiencyMetrics.includes(metricName)) return "efficiency";
      return "other";
    },
    [],
  );

  // Check if metric is inverted (lower is better)
  const isMetricInverted = useCallback((metricName: string): boolean => {
    return ["hallucination_rate", "duration", "cost"].includes(metricName);
  }, []);

  // Create column data structure for all columns
  const allColumnsData = useMemo<ColumnData<unknown>[]>(() => {
    const columns: ColumnData<unknown>[] = [];

    // Rank column (if enabled)
    if (showRank) {
      columns.push({
        id: "rank",
        label: "#",
        type: COLUMN_TYPE.number,
      });
    }

    // Standard columns
    if (displayColumns.includes("name")) {
      columns.push({
        id: "name",
        label: "Experiment",
        type: COLUMN_TYPE.string,
      });
    }
    if (displayColumns.includes("dataset")) {
      columns.push({
        id: "dataset",
        label: "Dataset",
        type: COLUMN_TYPE.string,
      });
    }

    // Metadata columns
    metadataColumns.forEach((metadataKey) => {
      columns.push({
        id: `metadata_${metadataKey}`,
        label: metadataKey
          .split("_")
          .map((w) => w.charAt(0).toUpperCase() + w.slice(1))
          .join(" "),
        type: COLUMN_TYPE.string,
      });
    });

    // Metric columns
    metricsToDisplay.forEach((metricName) => {
      columns.push({
        id: `metric_${metricName}`,
        label: formatMetricName(metricName),
        type: COLUMN_TYPE.number,
      });
    });

    // System metric columns (if not already in metricsToDisplay)
    if (
      displayColumns.includes("duration") &&
      !metricsToDisplay.includes("duration")
    ) {
      columns.push({
        id: "duration",
        label: "Duration",
        type: COLUMN_TYPE.number,
      });
    }
    if (displayColumns.includes("cost") && !metricsToDisplay.includes("cost")) {
      columns.push({
        id: "cost",
        label: "Cost",
        type: COLUMN_TYPE.number,
      });
    }
    if (
      displayColumns.includes("trace_count") &&
      !metricsToDisplay.includes("trace_count")
    ) {
      columns.push({
        id: "trace_count",
        label: "Traces",
        type: COLUMN_TYPE.number,
      });
    }
    if (displayColumns.includes("created_at")) {
      columns.push({
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.string,
      });
    }

    return columns;
  }, [
    showRank,
    displayColumns,
    metadataColumns,
    metricsToDisplay,
    formatMetricName,
  ]);

  // Get selected column IDs (all visible columns)
  const selectedColumnIds = useMemo(() => {
    return allColumnsData.map((col) => col.id);
  }, [allColumnsData]);

  // Apply column ordering
  const orderedColumns = useMemo(() => {
    return sortColumnsByOrder(allColumnsData, columnsOrder);
  }, [allColumnsData, columnsOrder]);

  // Handle column order change
  const handleColumnsOrderChange = useCallback(
    (newOrder: string[]) => {
      if (!sectionId || !widgetId) return;
      updateWidget(sectionId, widgetId, {
        config: {
          ...widgetConfig,
          columnsOrder: newOrder,
        },
      });
    },
    [sectionId, widgetId, widgetConfig, updateWidget],
  );

  // Extract metric value from experiment
  const getMetricValue = useCallback(
    (experiment: Experiment, metricName: string): number | null => {
      // Check feedback scores
      const feedbackScore = experiment.feedback_scores?.find(
        (s) => s.name === metricName,
      );
      if (feedbackScore) return feedbackScore.value;

      // Check experiment scores
      const experimentScore = experiment.experiment_scores?.find(
        (s) => s.name === metricName,
      );
      if (experimentScore) return experimentScore.value;

      // Check system metrics
      if (metricName === "duration" && experiment.duration) {
        return experiment.duration.p50 / 1000; // Convert ms to seconds (use p50 as average)
      }
      if (metricName === "cost" && experiment.total_estimated_cost != null) {
        return experiment.total_estimated_cost;
      }
      if (metricName === "trace_count") {
        return experiment.trace_count;
      }

      return null;
    },
    [],
  );

  // Extract metadata value from experiment
  const getMetadataValue = useCallback(
    (experiment: Experiment, metadataKey: string): string | null => {
      if (!experiment.metadata || typeof experiment.metadata !== "object") {
        return null;
      }
      const value = (experiment.metadata as Record<string, unknown>)[
        metadataKey
      ];
      if (value === null || value === undefined) {
        return null;
      }
      return String(value);
    },
    [],
  );

  // Calculate max value for a metric (for bar indicators) - memoized per metric
  const metricMaxValues = useMemo(() => {
    const maxMap: Record<string, number> = {};
    metricsToDisplay.forEach((metricName) => {
      const values = experiments
        .map((exp) => {
          const value = getMetricValue(exp, metricName);
          return value ?? 0;
        })
        .filter((v) => v !== null && v !== undefined && v > 0) as number[];
      if (values.length > 0) {
        maxMap[metricName] = Math.max(...values, 1);
      }
    });

    // Add system metrics
    const durationValues = experiments
      .map((exp) => (exp.duration ? exp.duration.p50 / 1000 : 0))
      .filter((v) => v > 0);
    if (durationValues.length > 0)
      maxMap.duration = Math.max(...durationValues, 1);

    const costValues = experiments
      .map((exp) => exp.total_estimated_cost ?? 0)
      .filter((v) => v > 0);
    if (costValues.length > 0) maxMap.cost = Math.max(...costValues, 1);

    const traceValues = experiments.map((exp) => exp.trace_count);
    if (traceValues.length > 0)
      maxMap.trace_count = Math.max(...traceValues, 1);

    return maxMap;
  }, [experiments, metricsToDisplay, getMetricValue]);

  // Transform experiments to leaderboard rows and calculate ranks
  const leaderboardRows = useMemo<LeaderboardRow[]>(() => {
    const rows = experiments.map((experiment) => {
      const metrics: Record<string, number | null> = {};
      metricsToDisplay.forEach((metricName) => {
        metrics[metricName] = getMetricValue(experiment, metricName);
      });

      const metadata: Record<string, string | null> = {};
      metadataColumns.forEach((metadataKey) => {
        metadata[metadataKey] = getMetadataValue(experiment, metadataKey);
      });

      return {
        rank: 0, // Will be calculated after sorting
        experiment,
        metrics,
        metadata,
      };
    });

    // Sort rows
    const sortedRows = [...rows].sort((a, b) => {
      const aValue = a.metrics[sortColumn] ?? -Infinity;
      const bValue = b.metrics[sortColumn] ?? -Infinity;

      if (sortDirection === "asc") {
        return aValue - bValue;
      } else {
        return bValue - aValue;
      }
    });

    // Assign ranks
    sortedRows.forEach((row, index) => {
      row.rank = index + 1;
    });

    return sortedRows;
  }, [
    experiments,
    metricsToDisplay,
    sortColumn,
    sortDirection,
    getMetricValue,
    metadataColumns,
    getMetadataValue,
  ]);

  // Handle column sort
  const handleSort = useCallback((metricName: string) => {
    setSortColumn((prev) => {
      if (prev === metricName) {
        setSortDirection((prevDir) =>
          prevDir === "desc" ? "asc" : prevDir === "asc" ? null : "desc",
        );
        return metricName;
      } else {
        setSortDirection("desc");
        return metricName;
      }
    });
  }, []);

  // Handle row click - navigate to experiments page with dataset filter
  const handleRowClick = useCallback(
    (experimentId: string, datasetId: string) => {
      navigate({
        to: "/$workspaceName/experiments/$datasetId/compare",
        params: {
          workspaceName,
          datasetId,
        },
        search: {
          experiments: [experimentId],
        },
      });
    },
    [navigate, workspaceName],
  );

  // Format metric value for display with tabular numerals
  const formatMetricValue = useCallback(
    (value: number | null, metricName: string): string => {
      if (value === null || value === undefined) {
        return "—";
      }

      // Format based on metric type with consistent precision
      if (metricName === "cost") {
        return `$${value.toFixed(2)}`;
      }
      if (metricName === "duration") {
        return `${value.toFixed(1)}s`;
      }
      if (metricName === "trace_count") {
        return value.toLocaleString();
      }

      // Default: format as decimal with 3 decimal places
      return value.toFixed(3);
    },
    [],
  );

  // Render sort icon with improved affordance
  const renderSortIcon = useCallback(
    (metricName: string) => {
      const isActive = sortColumn === metricName;
      const IconComponent = isActive
        ? sortDirection === "desc"
          ? ArrowDown
          : sortDirection === "asc"
            ? ArrowUp
            : ArrowUpDown
        : ArrowUpDown;

      return (
        <IconComponent
          className={cn(
            "ml-1.5 size-3.5 transition-opacity",
            isActive
              ? "text-foreground opacity-100"
              : "text-muted-foreground opacity-40 group-hover:opacity-70",
          )}
        />
      );
    },
    [sortColumn, sortDirection],
  );

  if (!widget) {
    return null;
  }

  const renderContent = () => {
    if (isPending) {
      return (
        <div className="flex size-full min-h-32 items-center justify-center">
          <Spinner />
        </div>
      );
    }

    if (leaderboardRows.length === 0) {
      return (
        <DashboardWidget.EmptyState
          title="No experiments found"
          message="Adjust your filters or add experiments to see the leaderboard"
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    // Group columns by category for visual grouping
    const groupedColumns = useMemo(() => {
      const groups: {
        quality: ColumnData<unknown>[];
        efficiency: ColumnData<unknown>[];
        other: ColumnData<unknown>[];
      } = { quality: [], efficiency: [], other: [] };

      orderedColumns.forEach((col) => {
        if (col.id === "rank" || col.id === "name" || col.id === "dataset") {
          groups.other.push(col);
        } else if (col.id.startsWith("metric_")) {
          const metricName = col.id.replace("metric_", "");
          const category = getMetricCategory(metricName);
          groups[category].push(col);
        } else if (["duration", "cost", "trace_count"].includes(col.id)) {
          groups.efficiency.push(col);
        } else {
          groups.other.push(col);
        }
      });

      return groups;
    }, [orderedColumns, getMetricCategory]);

    // Helper to render table header cell with improved styling
    const renderHeaderCell = (
      column: ColumnData<unknown>,
      group?: "quality" | "efficiency",
    ) => {
      const columnId = column.id;
      const isSortable =
        columnId.startsWith("metric_") ||
        ["duration", "cost", "trace_count"].includes(columnId);
      const sortKey = columnId.startsWith("metric_")
        ? columnId.replace("metric_", "")
        : columnId;
      const isActive = sortColumn === sortKey;

      // Get icon for metric
      const MetricIcon = isSortable && sortKey ? getMetricIcon(sortKey) : null;

      if (isSortable) {
        return (
          <TableHead
            key={columnId}
            className={cn(
              "group cursor-pointer select-none text-right transition-colors",
              density === "comfortable" ? "px-4 py-3" : "px-3 py-2",
              group === "quality" && "bg-primary/5",
              group === "efficiency" && "bg-blue-50/50 dark:bg-blue-950/20",
              isActive && "bg-primary/10 dark:bg-primary/20",
            )}
            onClick={() => handleSort(sortKey)}
          >
            <div className="flex items-center justify-end gap-1.5">
              {MetricIcon && (
                <MetricIcon className="size-3.5 text-muted-foreground opacity-60" />
              )}
              <span
                className={cn(
                  "font-medium",
                  isActive && "text-foreground",
                  !isActive && "text-muted-foreground",
                )}
              >
                {column.label}
              </span>
              {renderSortIcon(sortKey)}
            </div>
          </TableHead>
        );
      }

      // Special handling for rank and name (sticky columns)
      if (columnId === "rank") {
        return (
          <TableHead
            key={columnId}
            className={cn(
              "sticky left-0 z-10 bg-background text-center font-semibold",
              density === "comfortable" ? "w-20 px-3 py-3" : "w-16 px-2 py-2",
            )}
          >
            {column.label}
          </TableHead>
        );
      }

      if (columnId === "name") {
        return (
          <TableHead
            key={columnId}
            className={cn(
              "sticky z-10 bg-background font-semibold",
              density === "comfortable"
                ? "left-20 min-w-56 px-4 py-3"
                : "left-16 min-w-48 px-3 py-2",
            )}
          >
            {column.label}
          </TableHead>
        );
      }

      return (
        <TableHead
          key={columnId}
          className={cn(
            "min-w-32",
            density === "comfortable" ? "px-4 py-3" : "px-3 py-2",
          )}
        >
          {column.label}
        </TableHead>
      );
    };

    // Helper to render metric cell with bar indicator
    const renderMetricCell = (
      value: number | null,
      metricName: string,
      isInverted: boolean,
    ) => {
      if (value === null || value === undefined) {
        return <span className="tabular-nums text-muted-foreground">—</span>;
      }

      const maxValue = metricMaxValues[metricName] || 1;
      const percentage = Math.min((value / maxValue) * 100, 100);
      const displayPercentage = isInverted ? 100 - percentage : percentage;

      return (
        <div className="relative flex items-center justify-end gap-2">
          {/* Background bar indicator */}
          <div className="absolute right-0 h-1.5 w-16 rounded-full bg-muted opacity-20">
            <div
              className={cn(
                "h-full rounded-full transition-all",
                isInverted ? "bg-orange-500/40" : "bg-primary/30",
              )}
              style={{ width: `${displayPercentage}%` }}
            />
          </div>
          {/* Value with tabular numerals */}
          <span className="relative font-medium tabular-nums">
            {formatMetricValue(value, metricName)}
          </span>
        </div>
      );
    };

    // Helper to render table body cell with improved styling
    const renderBodyCell = (
      column: ColumnData<unknown>,
      row: LeaderboardRow,
      group?: "quality" | "efficiency",
    ) => {
      const columnId = column.id;
      const isTopThree = row.rank <= 3;
      const isFirst = row.rank === 1;

      if (columnId === "rank") {
        const RankIcon =
          row.rank === 1
            ? Trophy
            : row.rank === 2
              ? Medal
              : row.rank === 3
                ? Award
                : null;
        return (
          <TableCell
            key={columnId}
            className={cn(
              "sticky left-0 z-10 bg-background text-center",
              density === "comfortable" ? "px-3 py-4" : "px-2 py-3",
            )}
          >
            <div className="flex items-center justify-center">
              {RankIcon ? (
                <div
                  className={cn(
                    "flex items-center gap-1.5 rounded-md px-2 py-1",
                    row.rank === 1 &&
                      "bg-amber-50 text-amber-700 dark:bg-amber-950/30 dark:text-amber-400",
                    row.rank === 2 &&
                      "bg-slate-100 text-slate-600 dark:bg-slate-800/50 dark:text-slate-400",
                    row.rank === 3 &&
                      "bg-orange-50 text-orange-700 dark:bg-orange-950/30 dark:text-orange-400",
                  )}
                >
                  <RankIcon className="size-3.5" />
                  <span className="font-semibold tabular-nums">{row.rank}</span>
                </div>
              ) : (
                <span className="font-medium tabular-nums text-muted-foreground">
                  {row.rank}
                </span>
              )}
            </div>
          </TableCell>
        );
      }

      if (columnId === "name") {
        return (
          <TableCell
            key={columnId}
            className={cn(
              "sticky z-10 bg-background",
              density === "comfortable"
                ? "left-20 px-4 py-4"
                : "left-16 px-3 py-3",
              isTopThree && "border-l-2",
              row.rank === 1 &&
                "border-l-amber-500 bg-amber-50/30 dark:bg-amber-950/10",
              row.rank === 2 &&
                "border-l-slate-400 bg-slate-50/30 dark:bg-slate-900/20",
              row.rank === 3 &&
                "border-l-orange-500 bg-orange-50/30 dark:bg-orange-950/10",
            )}
          >
            <div className="flex flex-col gap-0.5">
              <span
                className={cn(
                  "line-clamp-1 font-medium transition-colors",
                  isFirst ? "text-foreground" : "text-primary",
                  "hover:underline",
                )}
              >
                {row.experiment.name}
              </span>
              {isFirst && (
                <span className="text-xs text-muted-foreground">
                  Best overall
                </span>
              )}
            </div>
          </TableCell>
        );
      }

      if (columnId === "dataset") {
        return (
          <TableCell
            key={columnId}
            className={cn(
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
            )}
          >
            <span className="text-xs text-muted-foreground">
              {row.experiment.dataset_name}
            </span>
          </TableCell>
        );
      }

      if (columnId.startsWith("metadata_")) {
        const metadataKey = columnId.replace("metadata_", "");
        return (
          <TableCell
            key={columnId}
            className={cn(
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
            )}
          >
            <span className="text-sm text-muted-foreground">
              {row.metadata[metadataKey] || "—"}
            </span>
          </TableCell>
        );
      }

      if (columnId.startsWith("metric_")) {
        const metricName = columnId.replace("metric_", "");
        const value = row.metrics[metricName];
        const inverted = isMetricInverted(metricName);
        return (
          <TableCell
            key={columnId}
            className={cn(
              "text-right",
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
              group === "quality" && "bg-primary/5",
              group === "efficiency" && "bg-blue-50/30 dark:bg-blue-950/10",
            )}
          >
            {renderMetricCell(value, metricName, inverted)}
          </TableCell>
        );
      }

      if (columnId === "duration") {
        const value = row.experiment.duration
          ? row.experiment.duration.p50 / 1000
          : null;
        return (
          <TableCell
            key={columnId}
            className={cn(
              "text-right",
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
              group === "efficiency" && "bg-blue-50/30 dark:bg-blue-950/10",
            )}
          >
            {renderMetricCell(value, "duration", true)}
          </TableCell>
        );
      }

      if (columnId === "cost") {
        const value = row.experiment.total_estimated_cost ?? null;
        return (
          <TableCell
            key={columnId}
            className={cn(
              "text-right",
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
              group === "efficiency" && "bg-blue-50/30 dark:bg-blue-950/10",
            )}
          >
            {renderMetricCell(value, "cost", true)}
          </TableCell>
        );
      }

      if (columnId === "trace_count") {
        return (
          <TableCell
            key={columnId}
            className={cn(
              "text-right tabular-nums",
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
            )}
          >
            <span className="font-medium">
              {formatMetricValue(row.experiment.trace_count, "trace_count")}
            </span>
          </TableCell>
        );
      }

      if (columnId === "created_at") {
        return (
          <TableCell
            key={columnId}
            className={cn(
              density === "comfortable" ? "px-4 py-4" : "px-3 py-3",
            )}
          >
            <span className="text-xs text-muted-foreground">
              {new Date(row.experiment.created_at).toLocaleDateString()}
            </span>
          </TableCell>
        );
      }

      return (
        <TableCell
          key={columnId}
          className={cn(density === "comfortable" ? "px-4 py-4" : "px-3 py-3")}
        >
          <span className="text-muted-foreground">—</span>
        </TableCell>
      );
    };

    return (
      <div className="flex size-full flex-col">
        {/* Density toggle - subtle control */}
        <div className="flex items-center justify-end gap-2 px-2 pb-1 opacity-0 transition-opacity group-hover:opacity-100">
          <button
            onClick={() =>
              setDensity(density === "comfortable" ? "compact" : "comfortable")
            }
            className="flex items-center gap-1 rounded-md px-2 py-1 text-xs text-muted-foreground transition-colors hover:bg-muted hover:text-foreground"
            aria-label={`Switch to ${
              density === "comfortable" ? "compact" : "comfortable"
            } density`}
          >
            {density === "comfortable" ? (
              <>
                <List className="size-3.5" />
                <span>Compact</span>
              </>
            ) : (
              <>
                <LayoutGrid className="size-3.5" />
                <span>Comfortable</span>
              </>
            )}
          </button>
        </div>

        {/* Table with improved styling */}
        <div className="flex-1 overflow-auto">
          <Table>
            <TableHeader className="sticky top-0 z-20 bg-background">
              <TableRow className="border-b-2">
                {/* Rank and Name columns */}
                {groupedColumns.other
                  .filter((col) => col.id === "rank" || col.id === "name")
                  .map((column) => renderHeaderCell(column))}

                {/* Dataset and metadata */}
                {groupedColumns.other
                  .filter(
                    (col) =>
                      col.id === "dataset" || col.id.startsWith("metadata_"),
                  )
                  .map((column) => renderHeaderCell(column))}

                {/* Quality metrics group */}
                {groupedColumns.quality.length > 0 && (
                  <>
                    {groupedColumns.quality.map((column) =>
                      renderHeaderCell(column, "quality"),
                    )}
                  </>
                )}

                {/* Efficiency metrics group */}
                {groupedColumns.efficiency.length > 0 && (
                  <>
                    {groupedColumns.efficiency.map((column) =>
                      renderHeaderCell(column, "efficiency"),
                    )}
                  </>
                )}

                {/* Other columns */}
                {groupedColumns.other
                  .filter(
                    (col) =>
                      col.id !== "rank" &&
                      col.id !== "name" &&
                      col.id !== "dataset" &&
                      !col.id.startsWith("metadata_"),
                  )
                  .map((column) => renderHeaderCell(column))}
              </TableRow>
            </TableHeader>
            <TableBody>
              {leaderboardRows.map((row) => {
                const isTopThree = row.rank <= 3;
                return (
                  <TableRow
                    key={row.experiment.id}
                    className={cn(
                      "cursor-pointer transition-all border-b",
                      density === "comfortable" ? "h-16" : "h-12",
                      isTopThree && "bg-primary/5 dark:bg-primary/5",
                      row.rank === 1 && "bg-amber-50/50 dark:bg-amber-950/10",
                      "hover:bg-primary/10 hover:shadow-sm",
                    )}
                    onClick={() =>
                      handleRowClick(
                        row.experiment.id,
                        row.experiment.dataset_id,
                      )
                    }
                    onKeyDown={(e) => {
                      if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        handleRowClick(
                          row.experiment.id,
                          row.experiment.dataset_id,
                        );
                      }
                    }}
                    tabIndex={0}
                    role="button"
                    aria-label={`View experiment ${row.experiment.name}, rank ${row.rank}`}
                  >
                    {/* Rank and Name columns */}
                    {groupedColumns.other
                      .filter((col) => col.id === "rank" || col.id === "name")
                      .map((column) => renderBodyCell(column, row))}

                    {/* Dataset and metadata */}
                    {groupedColumns.other
                      .filter(
                        (col) =>
                          col.id === "dataset" ||
                          col.id.startsWith("metadata_"),
                      )
                      .map((column) => renderBodyCell(column, row))}

                    {/* Quality metrics group */}
                    {groupedColumns.quality.map((column) =>
                      renderBodyCell(column, row, "quality"),
                    )}

                    {/* Efficiency metrics group */}
                    {groupedColumns.efficiency.map((column) =>
                      renderBodyCell(column, row, "efficiency"),
                    )}

                    {/* Other columns */}
                    {groupedColumns.other
                      .filter(
                        (col) =>
                          col.id !== "rank" &&
                          col.id !== "name" &&
                          col.id !== "dataset" &&
                          !col.id.startsWith("metadata_"),
                      )
                      .map((column) => renderBodyCell(column, row))}
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </div>
      </div>
    );
  };

  return (
    <DashboardWidget>
      {preview ? (
        <DashboardWidget.PreviewHeader />
      ) : (
        <DashboardWidget.Header
          title={widget.title || widget.generatedTitle || ""}
          subtitle={widget.subtitle}
          actions={
            <div className="flex items-center gap-1 opacity-0 transition-opacity group-hover:opacity-100">
              <ColumnsButton
                columns={allColumnsData}
                selectedColumns={selectedColumnIds}
                onSelectionChange={() => {}} // Column selection is handled by displayColumns
                order={columnsOrder}
                onOrderChange={handleColumnsOrderChange}
              />
              <DashboardWidget.ActionsMenu
                sectionId={sectionId!}
                widgetId={widgetId!}
                widgetTitle={widget.title}
              />
            </div>
          }
          dragHandle={<DashboardWidget.DragHandle />}
        />
      )}
      <DashboardWidget.Content>{renderContent()}</DashboardWidget.Content>
    </DashboardWidget>
  );
};

const arePropsEqual = (
  prev: DashboardWidgetComponentProps,
  next: DashboardWidgetComponentProps,
) => {
  if (prev.preview !== next.preview) return false;
  if (prev.preview && next.preview) return true;
  return prev.sectionId === next.sectionId && prev.widgetId === next.widgetId;
};

export default memo(ExperimentLeaderboardWidget, arePropsEqual);
