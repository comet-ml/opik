import React, { memo, useMemo, useCallback } from "react";
import { useShallow } from "zustand/react/shallow";
import isEmpty from "lodash/isEmpty";
import isNumber from "lodash/isNumber";
import uniq from "lodash/uniq";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import {
  useDashboardStore,
  selectRuntimeConfig,
  selectReadOnly,
} from "@/store/DashboardStore";
import {
  DashboardWidgetComponentProps,
  ExperimentsFeedbackScoresWidgetType,
} from "@/types/dashboard";
import { Filters } from "@/types/filters";
import { Groups } from "@/types/groups";
import { isFilterValid, extractExperimentIdsFilter } from "@/lib/filters";
import { isGroupValid, calculateGroupLabel } from "@/lib/groups";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import useExperimentsGroupsAggregations from "@/api/datasets/useExperimentsGroupsAggregations";
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
import { isEvalSuiteExperiment } from "@/lib/experiments";
import {
  renderScoreTooltipValue,
  getScoreDisplayName,
} from "@/lib/feedback-scores";
import { SCORE_TYPE_FEEDBACK, SCORE_TYPE_EXPERIMENT } from "@/types/shared";

const MAX_EXPERIMENTS_LIMIT = 100;
const PASS_RATE_LABEL = "Pass rate";

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

        // pass_rate is a top-level metric, not subject to feedbackScores filtering
        if (isNumber(value.aggregations.pass_rate)) {
          scores[PASS_RATE_LABEL] = value.aggregations.pass_rate;
          allLines.push(PASS_RATE_LABEL);
        }

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

    // pass_rate is a top-level metric, not subject to feedbackScores filtering
    if (isEvalSuiteExperiment(experiment) && isNumber(experiment.pass_rate)) {
      scores[PASS_RATE_LABEL] = experiment.pass_rate;
      allLines.push(PASS_RATE_LABEL);
    }

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
  const readOnly = useDashboardStore(selectReadOnly);

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

  const runtimeConfig = useDashboardStore(
    useShallow((state) => {
      const rc = selectRuntimeConfig(state);
      return {
        experimentIds: rc?.experimentIds || [],
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

  const hasRuntimeExperiments = (runtimeConfig.experimentIds?.length ?? 0) > 0;

  const { experimentIds: filterExperimentIds, remainingFilters } = useMemo(
    () => extractExperimentIdsFilter((widgetConfig?.filters || []) as Filters),
    [widgetConfig?.filters],
  );

  const experimentIds = hasRuntimeExperiments
    ? runtimeConfig.experimentIds
    : filterExperimentIds;

  const validFilters = useMemo(() => {
    if (hasRuntimeExperiments) return [];
    return remainingFilters.filter(isFilterValid);
  }, [hasRuntimeExperiments, remainingFilters]);

  const maxExperimentsCount =
    widgetConfig?.maxExperimentsCount ?? MAX_MAX_EXPERIMENTS;

  const validGroups = useMemo(() => {
    if (hasRuntimeExperiments) return [];
    const groups = (widgetConfig?.groups || []) as Groups;
    return groups.filter(isGroupValid);
  }, [hasRuntimeExperiments, widgetConfig?.groups]);

  const hasGroups = validGroups.length > 0;
  const hasExperimentIds = experimentIds.length > 0;

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
        experimentIds: hasExperimentIds ? experimentIds : undefined,
        page: 1,
        size: experimentsListSize,
      },
      {
        enabled: !hasGroups,
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
    experimentsData?.content,
    feedbackScores,
  ]);

  const isPending = hasGroups
    ? isGroupsAggregationsPending
    : isExperimentsPending;

  const noData =
    !isPending && chartData.data.every((record) => isEmpty(record.scores));

  const totalExperiments = experimentsData?.total ?? 0;
  const hasMoreThanLimit = !hasGroups && totalExperiments > experimentsListSize;

  const warningMessage = hasMoreThanLimit
    ? `Showing first ${experimentsListSize} of ${totalExperiments} experiments`
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
        : "Configure filters to display experiment metrics";

      return (
        <DashboardWidget.EmptyState
          title="No data available"
          message={emptyMessage}
          action={
            !preview && !readOnly ? (
              <DashboardWidget.EmptyState.EditAction
                label="Configure widget"
                onClick={handleEdit}
              />
            ) : undefined
          }
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
        <DashboardWidget.PreviewHeader />
      ) : (
        <DashboardWidget.Header
          title={widget.title || widget.generatedTitle || ""}
          subtitle={widget.subtitle}
          warningMessage={warningMessage}
          readOnly={readOnly}
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
