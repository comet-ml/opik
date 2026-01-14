import React, { memo, useMemo, useCallback } from "react";
import { useShallow } from "zustand/react/shallow";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnDef } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import DataTable from "@/components/shared/DataTable/DataTable";
import { Spinner } from "@/components/ui/spinner";

import {
  DashboardWidgetComponentProps,
  ExperimentLeaderboardWidgetType,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdateWidget,
  selectConfig,
} from "@/store/DashboardStore";
import useAppStore from "@/store/AppStore";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { Experiment } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import { getRowId } from "@/components/shared/DataTable/utils";
import {
  COLUMN_TYPE,
  AggregatedFeedbackScore,
  SCORE_TYPE_FEEDBACK,
  SCORE_TYPE_EXPERIMENT,
  COLUMN_EXPERIMENT_SCORES_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_METADATA_ID,
  DynamicColumn,
  ColumnData,
} from "@/types/shared";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import RankingCell from "@/components/shared/DataTableCells/RankingCell";
import RankingHeader from "@/components/shared/DataTableHeaders/RankingHeader";
import {
  formatConfigColumnName,
  PREDEFINED_COLUMNS,
  DEFAULT_MAX_ROWS,
  MAX_MAX_ROWS,
} from "./helpers";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";

const RANK_COLUMN_ID = "rank";
const NAME_COLUMN_ID = "name";

const ExperimentLeaderboardWidget: React.FunctionComponent<
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

  const updateWidget = useDashboardStore(selectUpdateWidget);
  const globalConfig = useDashboardStore(selectConfig);
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

  const config = useMemo(() => widgetConfig || {}, [widgetConfig]);

  const {
    dataSource = EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS,
    filters = [],
    selectedColumns = [],
    enableRanking = true,
    rankingMetric,
    columnsWidth = {},
    columnsOrder = [],
    scoresColumnsOrder = [],
    metadataColumnsOrder = [],
    maxRows,
    sorting: savedSorting = [],
    overrideDefaults = false,
  } = config;

  const maxRowsValue = useMemo(() => {
    return isNumber(maxRows) ? maxRows : DEFAULT_MAX_ROWS;
  }, [maxRows]);

  const experimentIds = useMemo(() => {
    if (overrideDefaults) {
      return widgetConfig?.experimentIds || [];
    }
    return globalConfig?.experimentIds || [];
  }, [
    globalConfig?.experimentIds,
    widgetConfig?.experimentIds,
    overrideDefaults,
  ]);

  const isSelectExperimentsMode =
    dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS;

  const { data: feedbackScoresData } = useExperimentsFeedbackScoresNames(
    {
      experimentsIds: isSelectExperimentsMode ? experimentIds : undefined,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => {
        const prefix =
          c.type === SCORE_TYPE_EXPERIMENT
            ? COLUMN_EXPERIMENT_SCORES_ID
            : COLUMN_FEEDBACK_SCORES_ID;
        return {
          id: `${prefix}.${c.name}`,
          label: c.name,
          columnType: COLUMN_TYPE.number,
          type: c.type,
        };
      });
  }, [feedbackScoresData?.scores]);

  // Use saved sorting if available, otherwise use ranking metric as default
  const apiSorting = useMemo(() => {
    if (savedSorting && savedSorting.length > 0) {
      return savedSorting;
    }
    if (enableRanking && rankingMetric) {
      return [{ id: rankingMetric, desc: false }];
    }
    return undefined;
  }, [savedSorting, enableRanking, rankingMetric]);

  const isRequestEnabled =
    dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
      ? experimentIds.length > 0
      : true;

  const { data: experimentsData, isPending } = useExperimentsList(
    {
      workspaceName,
      filters:
        dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP
          ? filters
          : undefined,
      experimentIds:
        dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
          ? experimentIds
          : undefined,
      sorting: apiSorting,
      page: 1,
      size:
        dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP
          ? maxRowsValue
          : MAX_MAX_ROWS,
    },
    {
      placeholderData: keepPreviousData,
      enabled: isRequestEnabled,
    },
  );

  const { data: rankingData } = useExperimentsList(
    {
      workspaceName,
      filters:
        dataSource === EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP
          ? filters
          : undefined,
      experimentIds:
        dataSource === EXPERIMENT_DATA_SOURCE.SELECT_EXPERIMENTS
          ? experimentIds
          : undefined,
      sorting: rankingMetric ? [{ id: rankingMetric, desc: true }] : undefined,
      page: 1,
      size: MAX_MAX_ROWS,
      queryKey: "experiments-ranking",
    },
    {
      placeholderData: keepPreviousData,
      enabled: isRequestEnabled && enableRanking && !!rankingMetric,
    },
  );

  const experiments = useMemo(
    () => experimentsData?.content ?? [],
    [experimentsData?.content],
  );

  const sortableColumns = useMemo(
    () => experimentsData?.sortable_by ?? [],
    [experimentsData?.sortable_by],
  );

  // Build ranking map: experimentId -> rank position
  const rankingMap = useMemo(() => {
    if (!enableRanking || !rankingData?.content) {
      return undefined;
    }
    const map = new Map<string, number>();
    rankingData.content.forEach((exp, index) => {
      map.set(exp.id, index + 1);
    });
    return map;
  }, [enableRanking, rankingData?.content]);

  // Build selected score columns with auto-add ranking metric
  const selectedScoreColumns = useMemo(() => {
    const cols = dynamicScoresColumns.filter((col) =>
      selectedColumns.includes(col.id),
    );

    // Auto-add ranking metric if enabled and not already selected
    if (enableRanking && rankingMetric) {
      const rankingCol = dynamicScoresColumns.find(
        (c) => c.id === rankingMetric,
      );
      if (rankingCol && !cols.find((c) => c.id === rankingMetric)) {
        cols.push(rankingCol);
      }
    }

    // Apply ordering
    const orderedCols = [...cols].sort((a, b) => {
      const indexA = scoresColumnsOrder.indexOf(a.id);
      const indexB = scoresColumnsOrder.indexOf(b.id);
      if (indexA === -1 && indexB === -1) return 0;
      if (indexA === -1) return 1;
      if (indexB === -1) return -1;
      return indexA - indexB;
    });

    return orderedCols;
  }, [
    dynamicScoresColumns,
    selectedColumns,
    enableRanking,
    rankingMetric,
    scoresColumnsOrder,
  ]);

  // Build metadata columns data
  const metadataColumnsData = useMemo<ColumnData<Experiment>[]>(() => {
    return selectedColumns
      .filter((colId) => colId.startsWith(`${COLUMN_METADATA_ID}.`))
      .map((colId) => {
        const metaKey = colId.substring(COLUMN_METADATA_ID.length + 1);
        return {
          id: colId,
          label: formatConfigColumnName(metaKey),
          type: COLUMN_TYPE.string,
          accessorFn: (row: Experiment) => get(row.metadata, metaKey),
          cell: AutodetectCell as never,
        };
      });
  }, [selectedColumns]);

  // Build feedback score columns data
  const scoresColumnsData = useMemo<ColumnData<Experiment>[]>(() => {
    const getScoreByName = (
      scores: AggregatedFeedbackScore[] | undefined,
      scoreName: string,
    ) => scores?.find((f) => f.name === scoreName);

    return selectedScoreColumns.map((scoreCol) => {
      const actualType = scoreCol.type || SCORE_TYPE_FEEDBACK;
      const isExperimentScore = actualType === SCORE_TYPE_EXPERIMENT;
      const scoresKey = isExperimentScore
        ? "experiment_scores"
        : "feedback_scores";

      return {
        id: scoreCol.id,
        label: scoreCol.label,
        type: scoreCol.columnType,
        accessorFn: (row: Experiment) => {
          const rowWithScores = row as Experiment & {
            feedback_scores?: AggregatedFeedbackScore[];
            experiment_scores?: AggregatedFeedbackScore[];
          };
          const scores = rowWithScores[scoresKey];
          return getScoreByName(scores, scoreCol.label);
        },
        header: FeedbackScoreHeader as never,
        cell: FeedbackScoreCell as never,
      };
    });
  }, [selectedScoreColumns]);

  // Build table columns
  const tableColumns = useMemo(() => {
    const allColumns: ColumnDef<Experiment>[] = [];

    // 1. Rank column (if enabled)
    if (enableRanking) {
      allColumns.push({
        id: RANK_COLUMN_ID,
        accessorFn: (row) => rankingMap?.get(row.id),
        header: RankingHeader as never,
        cell: RankingCell as never,
        enableSorting: false,
        size: 100,
        meta: {
          type: COLUMN_TYPE.number,
          header: "Rank",
        },
      });
    }

    // 2. Name column (always shown)
    allColumns.push(
      mapColumnDataFields<Experiment, Experiment>({
        id: NAME_COLUMN_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        sortable: sortableColumns?.includes("name") || false,
        customMeta: {
          nameKey: "name",
          idKey: "id",
          resource: RESOURCE_TYPE.experiment,
        },
      }),
    );

    // 3. Predefined columns (using helper)
    allColumns.push(
      ...convertColumnDataToColumn<Experiment, Experiment>(PREDEFINED_COLUMNS, {
        columnsOrder,
        selectedColumns,
        sortableColumns,
      }),
    );

    // 4. Metadata columns (using helper)
    allColumns.push(
      ...convertColumnDataToColumn<Experiment, Experiment>(
        metadataColumnsData,
        {
          columnsOrder: metadataColumnsOrder,
          sortableColumns,
        },
      ),
    );

    // 5. Feedback score columns (using helper)
    allColumns.push(
      ...convertColumnDataToColumn<Experiment, Experiment>(scoresColumnsData, {
        columnsOrder: scoresColumnsOrder,
        sortableColumns,
      }),
    );

    return allColumns;
  }, [
    enableRanking,
    rankingMap,
    selectedColumns,
    columnsOrder,
    metadataColumnsData,
    metadataColumnsOrder,
    scoresColumnsData,
    scoresColumnsOrder,
    sortableColumns,
  ]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: (updater: unknown) => {
        if (!preview && sectionId && widgetId) {
          const newWidths =
            typeof updater === "function"
              ? updater(columnsWidth)
              : (updater as Record<string, number>);
          updateWidget(sectionId, widgetId, {
            config: {
              ...config,
              columnsWidth: newWidths,
            },
          });
        }
      },
    }),
    [columnsWidth, preview, sectionId, widgetId, config, updateWidget],
  );

  const sortConfig = useMemo(() => {
    const sorting: Sorting = Array.isArray(savedSorting) ? savedSorting : [];

    return {
      enabled: true,
      enabledMultiSorting: false,
      sorting,
      setSorting: (updaterOrValue: unknown) => {
        if (!sectionId || !widgetId) return;

        const newSorting =
          typeof updaterOrValue === "function"
            ? updaterOrValue(sorting)
            : (updaterOrValue as Sorting);

        updateWidget(sectionId, widgetId, {
          config: {
            ...config,
            sorting: newSorting,
          },
        });
      },
    };
  }, [savedSorting, sectionId, widgetId, config, updateWidget]);

  if (!widget) {
    return null;
  }

  const noData = experiments.length === 0;

  const renderContent = () => {
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

    if (isPending && isRequestEnabled) {
      return (
        <div className="flex size-full min-h-32 items-center justify-center">
          <Spinner />
        </div>
      );
    }

    if (noData) {
      return (
        <DashboardWidget.EmptyState
          title="No data available"
          message="No experiments match the current filters"
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    return (
      <div className="h-full overflow-auto">
        <DataTable
          columns={tableColumns}
          data={experiments}
          resizeConfig={resizeConfig}
          sortConfig={sortConfig}
          getRowId={getRowId}
          noData={
            <DashboardWidget.EmptyState
              title="No data available"
              message="No experiments match the current filters"
            />
          }
        />
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
            <DashboardWidget.ActionsMenu
              sectionId={sectionId!}
              widgetId={widgetId!}
              widgetTitle={widget.title}
            />
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
