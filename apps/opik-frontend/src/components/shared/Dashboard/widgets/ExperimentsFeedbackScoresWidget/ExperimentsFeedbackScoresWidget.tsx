import React, { memo, useMemo, useCallback } from "react";
import { useShallow } from "zustand/react/shallow";
import isEmpty from "lodash/isEmpty";
import uniq from "lodash/uniq";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import { useDashboardStore, selectMixedConfig } from "@/store/DashboardStore";
import {
  DashboardWidgetComponentProps,
  EXPERIMENT_DATA_SOURCE,
  ExperimentsFeedbackScoresWidgetType,
} from "@/types/dashboard";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { isFilterValid } from "@/lib/filters";
import { isGroupValid, calculateGroupLabel } from "@/lib/groups";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useExperimentsGroupsAggregations from "@/api/datasets/useExperimentsGroupsAggregations";
import useExperimentsByIds from "@/api/datasets/useExperimenstByIds";
import useAppStore from "@/store/AppStore";
import { getDefaultHashedColorsChartConfig } from "@/lib/charts";
import { formatDate } from "@/lib/date";
import { CHART_TYPE } from "@/constants/chart";
import { ChartTooltipRenderHeaderArguments } from "@/components/shared/Charts/ChartTooltipContent/ChartTooltipContent";
import { Spinner } from "@/components/ui/spinner";
import {
  ExperimentsGroupNodeWithAggregations,
  Experiment,
} from "@/types/datasets";
import LineChart from "@/components/shared/Charts/LineChart/LineChart";
import BarChart from "@/components/shared/Charts/BarChart/BarChart";
import RadarChart from "@/components/shared/Charts/RadarChart/RadarChart";

const MAX_EXPERIMENTS_LIMIT = 100;
const MAX_SELECTED_EXPERIMENTS = 10;

type DataRecord = {
  entityId: string;
  entityName: string;
  createdDate: string;
  scores: Record<string, number>;
};

type ChartData = {
  data: DataRecord[];
  lines: string[];
};

const shouldIncludeScore = (
  scoreName: string,
  feedbackScores?: string[],
): boolean => {
  return (
    !feedbackScores ||
    feedbackScores.length === 0 ||
    feedbackScores.includes(scoreName)
  );
};

function transformGroupedExperimentsToChartData(
  groupsAggregationsData:
    | { content: Record<string, ExperimentsGroupNodeWithAggregations> }
    | undefined,
  validGroups: Groups,
  feedbackScores?: string[],
): ChartData {
  if (!groupsAggregationsData?.content) {
    return { data: [], lines: [] };
  }

  const data: DataRecord[] = [];
  const allLines: string[] = [];
  const isMultiLevel = validGroups.length > 1;

  const processGroup = (
    groupContent: Record<string, ExperimentsGroupNodeWithAggregations>,
    depth = 0,
    parentValues: string[] = [],
  ) => {
    const currentGroup = validGroups[depth];
    const fieldLabel = calculateGroupLabel(currentGroup);

    Object.entries(groupContent).forEach(([key, value]) => {
      const valueLabel = value.label || key || "Undefined";
      const currentValues = [...parentValues, valueLabel];
      const groupName = isMultiLevel
        ? currentValues.join(" / ")
        : `${fieldLabel}: ${valueLabel}`;
      const hasNestedGroups =
        value.groups && Object.keys(value.groups).length > 0;

      if (hasNestedGroups) {
        processGroup(value.groups!, depth + 1, currentValues);
      } else if (value.aggregations) {
        const scores: Record<string, number> = {};
        const aggregatedFeedbackScores =
          value.aggregations.feedback_scores || [];
        const aggregatedExperimentScores =
          value.aggregations.experiment_scores || [];

        aggregatedFeedbackScores.forEach((score) => {
          const scoreName = `${score.name} (avg)`;
          if (shouldIncludeScore(score.name, feedbackScores)) {
            scores[scoreName] = score.value;
            allLines.push(scoreName);
          }
        });

        aggregatedExperimentScores.forEach((score) => {
          if (shouldIncludeScore(score.name, feedbackScores)) {
            scores[score.name] = score.value;
            allLines.push(score.name);
          }
        });

        if (Object.keys(scores).length > 0) {
          data.push({
            entityId: groupName,
            entityName: groupName,
            createdDate: "",
            scores,
          });
        }
      }
    });
  };

  processGroup(groupsAggregationsData.content);

  return {
    data,
    lines: uniq(allLines),
  };
}

function transformUngroupedExperimentsToChartData(
  experiments: Experiment[],
  feedbackScores?: string[],
): ChartData {
  const allLines: string[] = [];

  const data: DataRecord[] = experiments.map((experiment) => {
    const scores: Record<string, number> = {};

    (experiment.feedback_scores || []).forEach((score) => {
      const scoreName = `${score.name} (avg)`;
      if (shouldIncludeScore(score.name, feedbackScores)) {
        scores[scoreName] = score.value;
        allLines.push(scoreName);
      }
    });

    (experiment.experiment_scores || []).forEach((score) => {
      if (shouldIncludeScore(score.name, feedbackScores)) {
        scores[score.name] = score.value;
        allLines.push(score.name);
      }
    });

    return {
      entityId: experiment.id,
      entityName: experiment.name,
      createdDate: formatDate(experiment.created_at),
      scores,
    };
  });

  return {
    data,
    lines: uniq(allLines),
  };
}

const ExperimentsFeedbackScoresWidget: React.FunctionComponent<
  DashboardWidgetComponentProps
> = ({ sectionId, widgetId, preview = false }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

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

  const globalConfig = useDashboardStore(
    useShallow((state) => {
      const config = selectMixedConfig(state);
      return {
        experimentIds: config?.experimentIds || [],
      };
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
    | ExperimentsFeedbackScoresWidgetType["config"]
    | undefined;

  const dataSource =
    widgetConfig?.dataSource || EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP;
  const chartType = widgetConfig?.chartType || CHART_TYPE.line;
  const feedbackScores = widgetConfig?.feedbackScores;

  const validFilters = useMemo(() => {
    const filters = (widgetConfig?.filters || []) as Filters;
    return filters.filter(isFilterValid);
  }, [widgetConfig?.filters]);

  const validGroups = useMemo(() => {
    const groups = (widgetConfig?.groups || []) as Groups;
    return groups.filter(isGroupValid);
  }, [widgetConfig?.groups]);

  const experimentIds = useMemo(() => {
    const localExperimentIds = widgetConfig?.experimentIds;
    if (localExperimentIds && localExperimentIds.length > 0) {
      return localExperimentIds;
    }
    return globalConfig.experimentIds || [];
  }, [globalConfig.experimentIds, widgetConfig?.experimentIds]);

  const isUsingGlobalExperiments =
    isEmpty(widgetConfig?.experimentIds) &&
    !isEmpty(globalConfig.experimentIds);

  const hasGroups = validGroups.length > 0;

  const isSelectExperimentsMode =
    dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

  const infoMessage =
    isUsingGlobalExperiments && isSelectExperimentsMode
      ? "Using the dashboard's default experiment settings"
      : undefined;

  // Limit to first 10 experiments
  const limitedExperimentIds = useMemo(
    () => experimentIds.slice(0, MAX_SELECTED_EXPERIMENTS),
    [experimentIds],
  );

  const hasMoreThanMaxSelected =
    experimentIds.length > MAX_SELECTED_EXPERIMENTS;

  const experimentsByIdsResults = useExperimentsByIds({
    experimentsIds: isSelectExperimentsMode ? limitedExperimentIds : [],
  });

  const { data: experimentsData, isPending: isExperimentsPending } =
    useExperimentsList(
      {
        workspaceName,
        filters: validFilters,
        page: 1,
        size: MAX_EXPERIMENTS_LIMIT,
      },
      {
        enabled: !hasGroups && !isSelectExperimentsMode,
      },
    );

  const {
    data: groupsAggregationsData,
    isPending: isGroupsAggregationsPending,
  } = useExperimentsGroupsAggregations(
    {
      workspaceName,
      filters: validFilters,
      groups: validGroups,
    },
    {
      enabled: hasGroups,
    },
  );

  const chartData = useMemo<ChartData>(() => {
    if (hasGroups) {
      return transformGroupedExperimentsToChartData(
        groupsAggregationsData,
        validGroups,
        feedbackScores,
      );
    }

    if (isSelectExperimentsMode) {
      const experiments = experimentsByIdsResults
        .map((result) => result.data)
        .filter((exp): exp is Experiment => exp !== undefined);
      return transformUngroupedExperimentsToChartData(
        experiments,
        feedbackScores,
      );
    }

    if (experimentsData?.content) {
      return transformUngroupedExperimentsToChartData(
        experimentsData.content,
        feedbackScores,
      );
    }

    return { data: [], lines: [] };
  }, [
    hasGroups,
    groupsAggregationsData,
    validGroups,
    isSelectExperimentsMode,
    experimentsByIdsResults,
    experimentsData?.content,
    feedbackScores,
  ]);

  const isExperimentsByIdsPending =
    isSelectExperimentsMode &&
    experimentsByIdsResults.some((result) => result.isPending);

  const isPending = hasGroups
    ? isGroupsAggregationsPending
    : isSelectExperimentsMode
      ? isExperimentsByIdsPending
      : isExperimentsPending;

  const noData =
    !isPending && chartData.data.every((record) => isEmpty(record.scores));

  const totalExperiments = experimentsData?.total ?? 0;
  const hasMoreThanLimit =
    !hasGroups &&
    !isSelectExperimentsMode &&
    totalExperiments > MAX_EXPERIMENTS_LIMIT;

  const warningMessage = hasMoreThanLimit
    ? `Showing first ${MAX_EXPERIMENTS_LIMIT} of ${totalExperiments} experiments`
    : isSelectExperimentsMode && hasMoreThanMaxSelected
      ? `Showing first ${MAX_SELECTED_EXPERIMENTS} of ${experimentIds.length} selected experiments`
      : undefined;

  const { transformedData, chartConfig } = useMemo(() => {
    if (chartType === CHART_TYPE.radar) {
      const entityNames = chartData.data.map((record) => record.entityName);
      const radarData = chartData.lines.map((scoreName) => {
        const point: Record<string, string | number> = { name: scoreName };
        chartData.data.forEach((record) => {
          if (record.scores[scoreName] !== undefined) {
            point[record.entityName] = record.scores[scoreName];
          }
        });
        return point;
      });
      return {
        transformedData: radarData,
        chartConfig: getDefaultHashedColorsChartConfig(entityNames),
      };
    }

    const flatData = chartData.data.map((record) => ({
      name: record.entityName,
      entityId: record.entityId,
      entityName: record.entityName,
      createdDate: record.createdDate,
      ...record.scores,
    }));
    return {
      transformedData: flatData,
      chartConfig: getDefaultHashedColorsChartConfig(chartData.lines),
    };
  }, [chartData, chartType]);

  const renderHeader = useCallback(
    ({ payload }: ChartTooltipRenderHeaderArguments) => {
      const { entityName, createdDate } = payload[0].payload;

      return (
        <>
          <div className="comet-body-xs-accented mb-0.5 line-clamp-3 max-w-64 break-words">
            {entityName}
          </div>
          {createdDate && (
            <div className="comet-body-xs mb-1 text-light-slate">
              {createdDate}
            </div>
          )}
        </>
      );
    },
    [],
  );

  if (!widget) {
    return null;
  }

  const renderChartContent = () => {
    if (isPending) {
      return (
        <div className="flex size-full min-h-32 items-center justify-center">
          <Spinner />
        </div>
      );
    }

    if (chartData.data.length === 0 || noData) {
      const hasFeedbackScoresFilter =
        feedbackScores && feedbackScores.length > 0;

      const emptyMessage = hasFeedbackScoresFilter
        ? "No data available for selected metrics"
        : isSelectExperimentsMode
          ? "Selected experiments have no feedback scores"
          : "Configure filters to display experiment metrics";

      return (
        <DashboardWidget.EmptyState
          title="No data available"
          message={emptyMessage}
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    if (chartType === CHART_TYPE.bar) {
      return (
        <BarChart
          chartId={`${widgetId}_chart`}
          config={chartConfig}
          data={transformedData}
          xAxisKey="name"
          renderTooltipHeader={renderHeader}
          className="size-full"
        />
      );
    }

    if (chartType === CHART_TYPE.radar) {
      return (
        <RadarChart
          chartId={`${widgetId}_chart`}
          config={chartConfig}
          data={transformedData}
          angleAxisKey="name"
          showLegend
          className="size-full"
        />
      );
    }

    return (
      <LineChart
        chartId={`${widgetId}_chart`}
        config={chartConfig}
        data={transformedData}
        xAxisKey="name"
        renderTooltipHeader={renderHeader}
        showArea={false}
        connectNulls={false}
        className="size-full"
      />
    );
  };

  return (
    <DashboardWidget>
      <DashboardWidget.Header
        title={widget.title}
        subtitle={widget.subtitle}
        warningMessage={warningMessage}
        infoMessage={infoMessage}
        preview={preview}
        actions={
          !preview ? (
            <DashboardWidget.ActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={widget.title}
            />
          ) : undefined
        }
        dragHandle={!preview ? <DashboardWidget.DragHandle /> : undefined}
      />
      <DashboardWidget.Content>{renderChartContent()}</DashboardWidget.Content>
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

export default memo(ExperimentsFeedbackScoresWidget, arePropsEqual);
