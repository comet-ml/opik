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
import useChartConfig from "@/hooks/useChartConfig";
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
import { MAX_MAX_EXPERIMENTS } from "@/lib/dashboard/utils";
import {
  renderScoreTooltipValue,
  getScoreDisplayName,
} from "@/lib/feedback-scores";
import { SCORE_TYPE_FEEDBACK, SCORE_TYPE_EXPERIMENT } from "@/types/shared";

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
  labelsMap?: Record<string, string>;
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

const createUniqueEntityLabels = (data: DataRecord[]): string[] => {
  const nameCounts: Record<string, number> = {};

  data.forEach((record) => {
    nameCounts[record.entityName] = (nameCounts[record.entityName] || 0) + 1;
  });

  return data.map((record) => {
    const isDuplicate = (nameCounts[record.entityName] || 0) > 1;
    return isDuplicate
      ? `${record.entityName} (${record.entityId})`
      : record.entityName;
  });
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
  const labelsMap: Record<string, string> = {};
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
          if (shouldIncludeScore(score.name, feedbackScores)) {
            scores[score.name] = score.value;
            allLines.push(score.name);
            labelsMap[score.name] = getScoreDisplayName(
              score.name,
              SCORE_TYPE_FEEDBACK,
            );
          }
        });

        aggregatedExperimentScores.forEach((score) => {
          const scoreName = getScoreDisplayName(
            score.name,
            SCORE_TYPE_EXPERIMENT,
          );
          if (shouldIncludeScore(score.name, feedbackScores)) {
            scores[scoreName] = score.value;
            allLines.push(scoreName);
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
    labelsMap,
  };
}

function transformUngroupedExperimentsToChartData(
  experiments: Experiment[],
  feedbackScores?: string[],
): ChartData {
  const allLines: string[] = [];
  const labelsMap: Record<string, string> = {};

  const data: DataRecord[] = experiments.map((experiment) => {
    const scores: Record<string, number> = {};

    (experiment.feedback_scores || []).forEach((score) => {
      if (shouldIncludeScore(score.name, feedbackScores)) {
        scores[score.name] = score.value;
        allLines.push(score.name);
        labelsMap[score.name] = getScoreDisplayName(
          score.name,
          SCORE_TYPE_FEEDBACK,
        );
      }
    });

    (experiment.experiment_scores || []).forEach((score) => {
      const scoreName = getScoreDisplayName(score.name, SCORE_TYPE_EXPERIMENT);
      if (shouldIncludeScore(score.name, feedbackScores)) {
        scores[scoreName] = score.value;
        allLines.push(scoreName);
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
    labelsMap,
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
        experimentDataSource: config?.experimentDataSource,
        experimentFilters: config?.experimentFilters || [],
        maxExperimentsCount: config?.maxExperimentsCount,
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

  const chartType = widgetConfig?.chartType || CHART_TYPE.line;
  const feedbackScores = widgetConfig?.feedbackScores;
  const overrideDefaults = widgetConfig?.overrideDefaults;

  const dataSource =
    (overrideDefaults
      ? widgetConfig?.dataSource
      : globalConfig.experimentDataSource) ??
    EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP;

  const validFilters = useMemo(() => {
    if (overrideDefaults) {
      const filters = (widgetConfig?.filters || []) as Filters;
      return filters.filter(isFilterValid);
    }
    return (globalConfig.experimentFilters || []).filter(isFilterValid);
  }, [overrideDefaults, widgetConfig?.filters, globalConfig.experimentFilters]);

  const maxExperimentsCount =
    (overrideDefaults
      ? widgetConfig?.maxExperimentsCount
      : globalConfig.maxExperimentsCount) ?? MAX_MAX_EXPERIMENTS;

  const validGroups = useMemo(() => {
    const groups = (widgetConfig?.groups || []) as Groups;
    return groups.filter(isGroupValid);
  }, [widgetConfig?.groups]);

  const experimentIds = useMemo(() => {
    if (overrideDefaults) {
      return widgetConfig?.experimentIds || [];
    }
    return globalConfig.experimentIds || [];
  }, [
    globalConfig.experimentIds,
    widgetConfig?.experimentIds,
    overrideDefaults,
  ]);

  const hasGroups = validGroups.length > 0;

  const isSelectExperimentsMode =
    dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

  const infoMessage = overrideDefaults
    ? "This widget uses custom experiments instead of the dashboard default."
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

  const experimentsListSize = useMemo(() => {
    if (maxExperimentsCount && maxExperimentsCount > 0) {
      return Math.min(maxExperimentsCount, MAX_EXPERIMENTS_LIMIT);
    }
    return MAX_EXPERIMENTS_LIMIT;
  }, [maxExperimentsCount]);

  const { data: experimentsData, isPending: isExperimentsPending } =
    useExperimentsList(
      {
        workspaceName,
        filters: validFilters,
        page: 1,
        size: experimentsListSize,
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
    totalExperiments > experimentsListSize;

  const warningMessage = hasMoreThanLimit
    ? `Showing first ${experimentsListSize} of ${totalExperiments} experiments`
    : isSelectExperimentsMode && hasMoreThanMaxSelected
      ? `Showing first ${MAX_SELECTED_EXPERIMENTS} of ${experimentIds.length} selected experiments`
      : undefined;

  const isRadarOrBar =
    chartType === CHART_TYPE.radar || chartType === CHART_TYPE.bar;

  const entityLabels = useMemo(
    () => createUniqueEntityLabels(chartData.data),
    [chartData.data],
  );

  const chartLines = isRadarOrBar ? entityLabels : chartData.lines;
  const chartConfig = useChartConfig(
    chartLines,
    isRadarOrBar ? undefined : chartData.labelsMap,
  );

  const transformedData = useMemo(() => {
    if (isRadarOrBar) {
      return chartData.lines.map((scoreName) => {
        const point: Record<string, string | number> = { name: scoreName };
        chartData.data.forEach((record, index) => {
          if (record.scores[scoreName] !== undefined) {
            point[entityLabels[index]] = record.scores[scoreName];
          }
        });
        return point;
      });
    }

    return chartData.data.map((record) => ({
      name: record.entityName,
      entityId: record.entityId,
      entityName: record.entityName,
      createdDate: record.createdDate,
      ...record.scores,
    }));
  }, [chartData, isRadarOrBar, entityLabels]);

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
    if (isSelectExperimentsMode && experimentIds.length === 0) {
      return (
        <DashboardWidget.EmptyState
          title="Experiments not configured"
          message="This widget needs experiments to display data. Select default experiments for the dashboard or set custom ones in the widget settings."
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

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
          renderTooltipValue={renderScoreTooltipValue}
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
          renderTooltipValue={renderScoreTooltipValue}
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
        renderTooltipValue={renderScoreTooltipValue}
        renderTooltipHeader={renderHeader}
        showArea={false}
        connectNulls={false}
        className="size-full"
      />
    );
  };

  return (
    <DashboardWidget>
      {preview ? (
        <DashboardWidget.PreviewHeader infoMessage={infoMessage} />
      ) : (
        <DashboardWidget.Header
          title={widget.title || widget.generatedTitle || ""}
          subtitle={widget.subtitle}
          warningMessage={warningMessage}
          infoMessage={infoMessage}
          actions={
            <DashboardWidget.ActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={widget.title}
            />
          }
          dragHandle={<DashboardWidget.DragHandle />}
        />
      )}
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
