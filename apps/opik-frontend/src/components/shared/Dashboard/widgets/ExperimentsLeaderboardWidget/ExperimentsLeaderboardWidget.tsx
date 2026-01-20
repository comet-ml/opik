import React, { memo, useMemo, useCallback } from "react";
import { useShallow } from "zustand/react/shallow";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnDef } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";
import { Trophy } from "lucide-react";

import DashboardWidget from "@/components/shared/Dashboard/DashboardWidget/DashboardWidget";
import DataTable from "@/components/shared/DataTable/DataTable";
import ExperimentsLeaderboardTableWrapper from "./ExperimentsLeaderboardTableWrapper";
import { Spinner } from "@/components/ui/spinner";
import { cn } from "@/lib/utils";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

import {
  DashboardWidgetComponentProps,
  ExperimentsLeaderboardWidgetType,
  EXPERIMENT_DATA_SOURCE,
} from "@/types/dashboard";
import {
  useDashboardStore,
  selectUpdateWidget,
  selectMixedConfig,
} from "@/store/DashboardStore";
import useAppStore from "@/store/AppStore";
import useExperimentsList from "@/api/datasets/useExperimentsList";
import { Experiment } from "@/types/datasets";
import { Sorting } from "@/types/sorting";
import { getRowId } from "@/components/shared/DataTable/utils";
import {
  COLUMN_TYPE,
  SCORE_TYPE_FEEDBACK,
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
  getExperimentListParams,
  isSelectExperimentsMode,
  parseScoreColumnId,
  getExperimentScore,
  buildScoreLabel,
  getRankingSorting,
  getRankingFilters,
} from "./helpers";
import {
  MIN_MAX_EXPERIMENTS,
  MAX_MAX_EXPERIMENTS,
  DEFAULT_MAX_EXPERIMENTS,
} from "@/lib/dashboard/utils";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";

const RANK_COLUMN_ID = "rank";
const NAME_COLUMN_ID = "name";

const ExperimentsLeaderboardWidget: React.FunctionComponent<
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

  const updateWidget = useDashboardStore(selectUpdateWidget);
  const onAddEditWidgetCallback = useDashboardStore(
    (state) => state.onAddEditWidgetCallback,
  );

  const handleEdit = useCallback(() => {
    if (sectionId && widgetId) {
      onAddEditWidgetCallback?.({ sectionId, widgetId });
    }
  }, [sectionId, widgetId, onAddEditWidgetCallback]);

  const widgetConfig = widget?.config as
    | ExperimentsLeaderboardWidgetType["config"]
    | undefined;

  const config = useMemo(() => widgetConfig || {}, [widgetConfig]);

  const {
    selectedColumns = [],
    enableRanking = true,
    rankingMetric,
    rankingDirection = true,
    columnsWidth = {},
    columnsOrder = [],
    scoresColumnsOrder = [],
    metadataColumnsOrder = [],
    sorting: savedSorting = [],
    overrideDefaults = false,
  } = config;

  const dataSource =
    (overrideDefaults
      ? widgetConfig?.dataSource
      : globalConfig.experimentDataSource) ??
    EXPERIMENT_DATA_SOURCE.FILTER_AND_GROUP;

  const filters = useMemo(() => {
    if (overrideDefaults) {
      return config.filters || [];
    }
    return globalConfig?.experimentFilters || [];
  }, [overrideDefaults, config.filters, globalConfig?.experimentFilters]);

  const maxRows =
    (overrideDefaults ? config.maxRows : globalConfig.maxExperimentsCount) ??
    MAX_MAX_EXPERIMENTS;

  const maxRowsValue = !isNumber(maxRows)
    ? DEFAULT_MAX_EXPERIMENTS
    : Math.max(MIN_MAX_EXPERIMENTS, Math.min(MAX_MAX_EXPERIMENTS, maxRows));

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

  const experimentListParams = getExperimentListParams({
    dataSource,
    experimentIds,
    filters,
  });

  const rankingSorting = useMemo(
    () => getRankingSorting({ rankingMetric, rankingDirection }),
    [rankingMetric, rankingDirection],
  );

  const apiSorting = useMemo(() => {
    if (savedSorting && savedSorting.length > 0) {
      return savedSorting;
    }
    if (enableRanking && rankingSorting) {
      return rankingSorting;
    }
    return undefined;
  }, [savedSorting, enableRanking, rankingSorting]);

  const { data: experimentsData, isPending } = useExperimentsList(
    {
      workspaceName,
      filters: experimentListParams.filters,
      experimentIds: experimentListParams.experimentIds,
      sorting: apiSorting,
      page: 1,
      size: isSelectExperimentsMode(dataSource)
        ? MAX_MAX_EXPERIMENTS
        : maxRowsValue,
    },
    {
      placeholderData: keepPreviousData,
      enabled: experimentListParams.isEnabled,
    },
  );

  const rankingFilters = useMemo(
    () => getRankingFilters(rankingMetric, experimentListParams.filters),
    [rankingMetric, experimentListParams.filters],
  );

  const { data: rankingData } = useExperimentsList(
    {
      workspaceName,
      filters: rankingFilters,
      experimentIds: experimentListParams.experimentIds,
      sorting: rankingSorting,
      page: 1,
      size: MAX_MAX_EXPERIMENTS,
      queryKey: "experiments-ranking",
    },
    {
      placeholderData: keepPreviousData,
      enabled:
        experimentListParams.isEnabled && enableRanking && !!rankingMetric,
    },
  );

  const experiments = experimentsData?.content ?? [];
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

  const selectedScoreColumns = useMemo(() => {
    const scoreColumnIds = [...selectedColumns];

    if (
      enableRanking &&
      rankingMetric &&
      !scoreColumnIds.includes(rankingMetric)
    ) {
      scoreColumnIds.push(rankingMetric);
    }

    return scoreColumnIds
      .map((colId) => {
        const parsed = parseScoreColumnId(colId);
        if (!parsed) return null;
        return {
          id: colId,
          label: buildScoreLabel(parsed.scoreName, parsed.scoreType),
          columnType: COLUMN_TYPE.number,
          type: parsed.scoreType,
        } as DynamicColumn;
      })
      .filter((col): col is DynamicColumn => col !== null);
  }, [selectedColumns, enableRanking, rankingMetric]);

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

  const scoresColumnsData = useMemo<ColumnData<Experiment>[]>(() => {
    return selectedScoreColumns.map((scoreCol) => {
      const actualType = scoreCol.type || SCORE_TYPE_FEEDBACK;
      const isRankingMetric = enableRanking && scoreCol.id === rankingMetric;

      return {
        id: scoreCol.id,
        label: scoreCol.label,
        type: scoreCol.columnType,
        scoreType: actualType,
        accessorFn: (row: Experiment) => getExperimentScore(scoreCol.id, row),
        header: FeedbackScoreHeader as never,
        cell: FeedbackScoreCell as never,
        customMeta: isRankingMetric
          ? {
              prefixIcon: (
                <TooltipWrapper
                  content={`Ranking metric (${
                    rankingDirection ? "higher is better" : "lower is better"
                  })`}
                >
                  <Trophy className="mr-1 size-3.5 shrink-0 text-yellow-500" />
                </TooltipWrapper>
              ),
            }
          : undefined,
      };
    });
  }, [selectedScoreColumns, enableRanking, rankingMetric, rankingDirection]);

  // Build table columns
  const tableColumns = useMemo(() => {
    const allColumns: ColumnDef<Experiment>[] = [];

    // 1. Rank column (if enabled)
    if (enableRanking) {
      allColumns.push(
        mapColumnDataFields<Experiment, Experiment>({
          id: RANK_COLUMN_ID,
          label: "Rank",
          type: COLUMN_TYPE.number,
          header: RankingHeader as never,
          cell: RankingCell as never,
          size: 50,
          sortable: false,
          customMeta: {
            getRank: (rowId: string) => rankingMap?.get(rowId),
          },
        }),
      );
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
          idKey: "dataset_id",
          resource: RESOURCE_TYPE.experiment,
          getSearch: (data: Experiment) => ({
            experiments: [data.id],
          }),
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
    const sorting: Sorting = isArray(savedSorting) ? savedSorting : [];

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
    if (isSelectExperimentsMode(dataSource) && experimentIds.length === 0) {
      return (
        <DashboardWidget.EmptyState
          title="Experiments not configured"
          message="This widget needs experiments to display data. Select default experiments for the dashboard or set custom ones in the widget settings."
          onAction={!preview ? handleEdit : undefined}
          actionLabel="Configure widget"
        />
      );
    }

    if (isPending && experimentListParams.isEnabled) {
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
      <div
        className={cn(
          "h-full",
          preview &&
            "[&_a]:pointer-events-none [&_button]:pointer-events-none [&_th]:pointer-events-none",
        )}
      >
        <DataTable
          columns={tableColumns}
          data={experiments}
          resizeConfig={preview ? undefined : resizeConfig}
          sortConfig={preview ? undefined : sortConfig}
          getRowId={getRowId}
          TableWrapper={ExperimentsLeaderboardTableWrapper}
          stickyHeader
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

export default memo(ExperimentsLeaderboardWidget, arePropsEqual);
