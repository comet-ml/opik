import React, { useCallback, useMemo, useState } from "react";
import { ChartLine } from "lucide-react";
import {
  CellContext,
  ColumnSort,
  Row,
  RowSelectionState,
} from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useTablePageSize from "@/hooks/useTablePageSize";
import get from "lodash/get";
import uniq from "lodash/uniq";
import isNumber from "lodash/isNumber";
import isObject from "lodash/isObject";

import DataTable from "@/shared/DataTable/DataTable";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import IdCell from "@/shared/DataTableCells/IdCell";
import CommentsCell from "@/shared/DataTableCells/CommentsCell";
import CostCell from "@/shared/DataTableCells/CostCell";
import PassRateCell from "@/shared/DataTableCells/PassRateCell";
import CodeCell from "@/shared/DataTableCells/CodeCell";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import useAppStore, { useActiveProjectId } from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import {
  transformExperimentScores,
  getScoreDisplayName,
} from "@/lib/feedback-scores";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_DATASET_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  SCORE_TYPE_FEEDBACK,
} from "@/types/shared";
import { DELETED_ENTITY_LABEL } from "@/constants/groups";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import ExperimentsActionsPanel from "@/v2/pages-shared/experiments/ExperimentsActionsPanel/ExperimentsActionsPanel";
import ExperimentRowActionsCell from "@/v2/pages/ExperimentsPage/ExperimentRowActionsCell";
import FeedbackScoresChartsWrapper from "@/v2/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartsWrapper";
import SearchInput from "@/shared/SearchInput/SearchInput";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import { Separator } from "@/ui/separator";
import { Card } from "@/ui/card";
import useGroupedExperimentsList, {
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import { useExperimentsTableConfig } from "@/v2/pages-shared/experiments/useExperimentsTableConfig";
import useExperimentsPinning from "@/v2/pages-shared/experiments/useExperimentsPinning";
import { useExperimentsGroupsAndFilters } from "@/v2/pages-shared/experiments/useExperimentsGroupsAndFilters";
import { useExperimentsFeedbackScores } from "@/v2/pages-shared/experiments/useExperimentsFeedbackScores";
import { useExperimentsAutoExpandingLogic } from "@/v2/pages-shared/experiments/useExperimentsAutoExpandingLogic";
import { useExpandingConfig } from "@/v2/pages-shared/experiments/useExpandingConfig";
import { getIsGroupRow, renderCustomRow } from "@/shared/DataTable/utils";
import { migrateLegacyExperimentsStorage } from "./migrateLegacyExperimentsStorage";
import { calculateGroupLabel, isGroupFullyExpanded } from "@/lib/groups";
import MultiResourceCell from "@/shared/DataTableCells/MultiResourceCell";
import FeedbackScoreListCell from "@/shared/DataTableCells/FeedbackScoreListCell";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTableVirtualBody from "@/shared/DataTable/DataTableVirtualBody";
import { ChartData } from "@/v2/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";
import GroupsButton from "@/shared/GroupsButton/GroupsButton";
import TextCell from "@/shared/DataTableCells/TextCell";
import ItemSourceCell from "@/v2/pages-shared/experiments/ItemSourceCell";
import {
  DATASET_TYPE,
  EVALUATION_METHOD,
  EXPERIMENT_STATUS,
} from "@/types/datasets";
import { Filter } from "@/types/filters";
import DatasetVersionCell from "@/v2/pages-shared/experiments/DatasetVersionCell";
import { formatPromptVersionLabel } from "@/lib/experiments";
import { Skeleton } from "@/ui/skeleton";

const PASS_RATE_LABEL = "Pass rate";

const withRunningSkeleton = <TValue,>(
  Cell: React.ComponentType<CellContext<GroupedExperiment, TValue>>,
) => {
  const WrappedCell = (context: CellContext<GroupedExperiment, TValue>) => {
    const { row } = context;
    if (
      !getIsGroupRow(row) &&
      row.original?.status === EXPERIMENT_STATUS.RUNNING
    ) {
      return (
        <div className="flex size-full items-center p-2">
          <Skeleton className="size-full" />
        </div>
      );
    }
    return <Cell {...context} />;
  };
  return WrappedCell;
};

const COLUMN_DATASET_VERSION_ID = "dataset_version";
const NO_DYNAMIC_SCORES_COLUMNS: DynamicColumn[] = [];

type TabConfig = {
  storageKeyPrefix: string;
  datasetType: DATASET_TYPE;
  itemSourceLabel: string;
  versionLabel: string;
  excludedColumnIds: string[];
  defaultSelectedColumns: string[];
  // Legacy experiments store evaluation_method 'unknown', so the Datasets tab
  // matches everything that is not a test suite rather than = 'dataset'.
  evaluationMethodOperator: Filter["operator"];
};

const TAB_CONFIG: Record<EVALUATION_METHOD, TabConfig> = {
  [EVALUATION_METHOD.TEST_SUITE]: {
    storageKeyPrefix: "experiments-test-suites",
    datasetType: DATASET_TYPE.TEST_SUITE,
    itemSourceLabel: "Test suite",
    versionLabel: "Test suite version",
    excludedColumnIds: [COLUMN_FEEDBACK_SCORES_ID],
    defaultSelectedColumns: [
      COLUMN_NAME_ID,
      COLUMN_DATASET_VERSION_ID,
      "pass_rate",
      "total_estimated_cost_avg",
      "trace_count",
      "duration.p50",
      "created_at",
      "prompt",
    ],
    evaluationMethodOperator: "=",
  },
  [EVALUATION_METHOD.DATASET]: {
    storageKeyPrefix: "experiments-datasets",
    datasetType: DATASET_TYPE.DATASET,
    itemSourceLabel: "Dataset",
    versionLabel: "Dataset version",
    excludedColumnIds: ["pass_rate"],
    defaultSelectedColumns: [
      COLUMN_NAME_ID,
      COLUMN_DATASET_VERSION_ID,
      COLUMN_FEEDBACK_SCORES_ID,
      "trace_count",
      "duration.p50",
      "created_at",
      "prompt",
    ],
    evaluationMethodOperator: "!=",
  },
};

export const getEvaluationMethodFilter = (
  evaluationMethod: EVALUATION_METHOD,
): Filter => ({
  id: "evaluation_method",
  field: "evaluation_method",
  type: COLUMN_TYPE.string,
  operator: TAB_CONFIG[evaluationMethod].evaluationMethodOperator,
  value: EVALUATION_METHOD.TEST_SUITE,
});

const DEFAULT_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_DATASET_VERSION_ID,
  "pass_rate",
  COLUMN_FEEDBACK_SCORES_ID,
  "total_estimated_cost_avg",
  "trace_count",
  "duration.p50",
  "duration.p90",
  "duration.p99",
  "created_at",
  "prompt",
  COLUMN_DATASET_ID,
  "total_estimated_cost",
  COLUMN_COMMENTS_ID,
  "tags",
  COLUMN_METADATA_ID,
  "created_by",
];

export const MAX_EXPANDED_DEEPEST_GROUPS = 5;

type GeneralDatasetsTabProps = {
  evaluationMethod: EVALUATION_METHOD;
  isExistencePending?: boolean;
};

const GeneralDatasetsTab: React.FC<GeneralDatasetsTabProps> = ({
  evaluationMethod,
  isExistencePending = false,
}) => {
  const {
    storageKeyPrefix,
    datasetType,
    itemSourceLabel,
    versionLabel,
    excludedColumnIds,
    defaultSelectedColumns,
  } = TAB_CONFIG[evaluationMethod];
  const isTestSuiteTab = evaluationMethod === EVALUATION_METHOD.TEST_SUITE;
  // Must run before the storage hooks below read their keys.
  useState(() =>
    migrateLegacyExperimentsStorage({
      storageKeyPrefix,
      excludedColumnIds,
      addedColumnIds: [COLUMN_DATASET_VERSION_ID],
    }),
  );
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const activeProjectId = useActiveProjectId();
  const navigate = useNavigate();
  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useTablePageSize(
    `${storageKeyPrefix}-pagination-size`,
  );

  const [groupLimit, setGroupLimit] = useQueryParam<Record<string, number>>(
    "limits",
    { ...JsonParam, default: {} },
    {
      updateType: "replaceIn",
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [pinnedIds, setPinnedIds] = useLocalStorageState<string[]>(
    `${storageKeyPrefix}-pinned-experiments-${activeProjectId}`,
    { defaultValue: [] },
  );

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    `${storageKeyPrefix}-columns-sort`,
    {
      defaultValue: [],
    },
  );

  const columnsDef: ColumnData<GroupedExperiment>[] = useMemo(() => {
    const allColumns: ColumnData<GroupedExperiment>[] = [
      {
        id: COLUMN_NAME_ID,
        label: "Name",
        type: COLUMN_TYPE.string,
        cell: TextCell as never,
        sortable: true,
        size: 200,
      },
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
      {
        id: COLUMN_DATASET_VERSION_ID,
        label: versionLabel,
        type: COLUMN_TYPE.string,
        cell: DatasetVersionCell as never,
      },
      {
        id: COLUMN_DATASET_ID,
        label: itemSourceLabel,
        type: COLUMN_TYPE.string,
        cell: ItemSourceCell as never,
        customMeta: {
          nameKey: "dataset_name",
          idKey: "dataset_id",
        },
      },
      {
        id: "created_at",
        label: "Created",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.created_at),
      },
      {
        id: "created_by",
        label: "Created by",
        type: COLUMN_TYPE.string,
      },
      {
        id: "duration.p50",
        label: "Duration (avg.)",
        type: COLUMN_TYPE.duration,
        accessorFn: (row) => row.duration?.p50,
        cell: withRunningSkeleton(DurationCell) as never,
        aggregatedCell: DurationCell.Aggregation as never,
        customMeta: {
          aggregationKey: "duration.p50",
        },
      },
      {
        id: "duration.p90",
        label: "Duration (p90)",
        type: COLUMN_TYPE.duration,
        accessorFn: (row) => row.duration?.p90,
        cell: withRunningSkeleton(DurationCell) as never,
        aggregatedCell: DurationCell.Aggregation as never,
        customMeta: {
          aggregationKey: "duration.p90",
        },
      },
      {
        id: "duration.p99",
        label: "Duration (p99)",
        type: COLUMN_TYPE.duration,
        accessorFn: (row) => row.duration?.p99,
        cell: withRunningSkeleton(DurationCell) as never,
        aggregatedCell: DurationCell.Aggregation as never,
        customMeta: {
          aggregationKey: "duration.p99",
        },
      },
      {
        id: "prompt",
        label: "Prompt",
        type: COLUMN_TYPE.list,
        // Show the prompt name plus its version (OPIK-6838). Unlike the Prompt
        // Library experiments tab, where the prompt is implied by the page
        // context, this global table needs the name too.
        accessorFn: (row) =>
          (row.prompt_versions ?? []).map((v) => ({
            ...v,
            version_label: formatPromptVersionLabel(v),
          })),
        cell: MultiResourceCell as never,
        customMeta: {
          nameKey: "version_label",
          idKey: "prompt_id",
          resource: RESOURCE_TYPE.prompt,
          getSearch: (data: GroupedExperiment) => ({
            activeVersionId: get(data, "id", null),
          }),
        },
        explainer: EXPLAINERS_MAP[EXPLAINER_ID.whats_a_prompt_commit],
      },
      {
        id: "trace_count",
        label: "Trace count",
        type: COLUMN_TYPE.number,
        cell: withRunningSkeleton(TextCell) as never,
        aggregatedCell: TextCell.Aggregation as never,
        customMeta: {
          aggregationKey: "trace_count",
        },
      },
      {
        id: "total_estimated_cost",
        label: "Total estimated cost",
        type: COLUMN_TYPE.cost,
        cell: withRunningSkeleton(CostCell) as never,
        aggregatedCell: CostCell.Aggregation as never,
        customMeta: {
          aggregationKey: "total_estimated_cost",
        },
      },
      {
        id: "total_estimated_cost_avg",
        label: "Cost per trace (avg.)",
        type: COLUMN_TYPE.cost,
        cell: withRunningSkeleton(CostCell) as never,
        aggregatedCell: CostCell.Aggregation as never,
        customMeta: {
          aggregationKey: "total_estimated_cost_avg",
        },
      },
      {
        id: "pass_rate",
        label: "Pass rate",
        type: COLUMN_TYPE.number,
        iconType: "pass_rate",
        accessorFn: (row) => row.pass_rate,
        cell: withRunningSkeleton(PassRateCell) as never,
        aggregatedCell: PassRateCell.Aggregation as never,
        customMeta: {
          aggregationKey: "pass_rate",
        },
      },
      {
        id: COLUMN_FEEDBACK_SCORES_ID,
        label: "Feedback Scores",
        type: COLUMN_TYPE.numberDictionary,
        accessorFn: transformExperimentScores,
        cell: withRunningSkeleton(FeedbackScoreListCell) as never,
        aggregatedCell: FeedbackScoreListCell.Aggregation as never,
        customMeta: {
          getHoverCardName: (row: GroupedExperiment) => row.name,
          areAggregatedScores: true,
          aggregationKey: "feedback_scores",
        },
        explainer: EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores],
      },
      {
        id: COLUMN_COMMENTS_ID,
        label: "Comments",
        type: COLUMN_TYPE.string,
        cell: CommentsCell as never,
      },
      {
        id: "tags",
        label: "Tags",
        type: COLUMN_TYPE.list,
        iconType: "tags" as const,
        cell: ListCell as never,
      },
      {
        id: COLUMN_METADATA_ID,
        label: "Configuration",
        type: COLUMN_TYPE.dictionary,
        accessorFn: (row) =>
          isObject(row.metadata)
            ? JSON.stringify(row.metadata, null, 2)
            : row.metadata,
        cell: CodeCell as never,
      },
    ];
    return allColumns.filter(
      (column) => !excludedColumnIds.includes(column.id),
    );
  }, [versionLabel, itemSourceLabel, excludedColumnIds]);

  const evaluationMethodFilters = useMemo<Filter[]>(
    () => [getEvaluationMethodFilter(evaluationMethod)],
    [evaluationMethod],
  );

  const queryFilters = useMemo<Filter[]>(
    () => [...filters, ...evaluationMethodFilters],
    [filters, evaluationMethodFilters],
  );

  const { isFeedbackScoresPending, dynamicScoresColumns } =
    useExperimentsFeedbackScores({ projectId: activeProjectId ?? undefined });

  const { groups, setGroups, filtersAndGroupsConfig, filterAndGroupColumns } =
    useExperimentsGroupsAndFilters({
      storageKeyPrefix,
      sortedColumns,
      filters: queryFilters,
      projectId: activeProjectId,
      datasetType,
      itemSourceLabel,
    });

  const expandingConfig = useExpandingConfig({
    groups,
    maxExpandedDeepestGroups: MAX_EXPANDED_DEEPEST_GROUPS,
  });

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useGroupedExperimentsList({
      workspaceName,
      projectId: activeProjectId ?? undefined,
      groupLimit,
      filters: queryFilters,
      sorting: sortedColumns,
      groups,
      search: search!,
      page: page!,
      size: size!,
      expandedMap: expandingConfig.expanded as Record<string, boolean>,
      polling: true,
    });

  const loadedExperiments = useMemo(() => data?.content ?? [], [data?.content]);
  const {
    experiments,
    pinningConfig,
    refetch: refetchPinned,
  } = useExperimentsPinning({
    rows: loadedExperiments,
    workspaceName,
    projectId: activeProjectId ?? undefined,
    pinnedIds,
    setPinnedIds,
    filters: evaluationMethodFilters,
    enabled: groups.length === 0,
    polling: true,
  });

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const flattenGroups = useMemo(
    () => data?.flattenGroups ?? [],
    [data?.flattenGroups],
  );

  const aggregationMap = useMemo(
    () => data?.aggregationMap ?? {},
    [data?.aggregationMap],
  );

  const datasetTypeMap = useMemo(
    () => data?.datasetTypeMap ?? {},
    [data?.datasetTypeMap],
  );

  useExperimentsAutoExpandingLogic({
    groups,
    flattenGroups,
    isPending,
    isPlaceholderData,
    maxExpandedDeepestGroups: MAX_EXPANDED_DEEPEST_GROUPS,
    setExpanded: expandingConfig.setExpanded,
  });

  const total = data?.total ?? 0;

  const {
    columns,
    selectedRows,
    sortConfig,
    resizeConfig,
    columnPinningConfig,
    groupingConfig,
    columnSections,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    groupFieldNames,
    getExperimentRowId,
    getExperimentRowClassName,
  } = useExperimentsTableConfig({
    storageKeyPrefix,
    defaultColumns: columnsDef,
    defaultSelectedColumns,
    defaultColumnsOrder: DEFAULT_COLUMNS_ORDER,
    groups,
    sortableBy,
    dynamicScoresColumns: isTestSuiteTab
      ? NO_DYNAMIC_SCORES_COLUMNS
      : dynamicScoresColumns,
    experiments,
    rowSelection,
    actionsCell: ExperimentRowActionsCell,
    sortedColumns,
    setSortedColumns,
    datasetTypeMap,
    itemSourceLabel,
  });

  const handleRowClick = useCallback(
    (row: GroupedExperiment) => {
      navigate({
        to: "/$workspaceName/projects/$projectId/experiments/$datasetId/compare",
        params: {
          datasetId: row.dataset_id,
          workspaceName,
          projectId: activeProjectId!,
        },
        search: {
          experiments: [row.id],
        },
      });
    },
    [navigate, workspaceName, activeProjectId],
  );

  const handleClearFilters = useCallback(() => {
    setSearch(undefined);
    setFilters([]);
  }, [setSearch, setFilters]);

  const hasGroups = Boolean(groups.length);

  const renderCustomRowCallback = useCallback(
    (row: Row<GroupedExperiment>) => {
      return renderCustomRow(row, setGroupLimit);
    },
    [setGroupLimit],
  );

  // Name becomes the grouping anchor when any grouping is active and is rendered
  // by the group cell, so hide it from the columns menu in that case.
  const availableColumns = useMemo(() => {
    return columnsDef.filter((col) => {
      if (groups.length > 0 && col.id === COLUMN_NAME_ID) return false;
      return true;
    });
  }, [groups, columnsDef]);

  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};

    // Handle no grouping case - group by dataset for charts
    const deepestExpandedGroups = !hasGroups
      ? Object.entries(
          experiments.reduce<Record<string, GroupedExperiment[]>>(
            (acc, exp) => {
              const datasetId = exp.dataset_id || "no_dataset";
              if (!acc[datasetId]) {
                acc[datasetId] = [];
              }
              acc[datasetId].push(exp);
              return acc;
            },
            {},
          ),
        ).map(([datasetId, datasetExperiments]) => ({
          id: datasetId,
          name: [
            {
              label: itemSourceLabel,
              value: datasetExperiments[0]?.dataset_name || "Undefined",
            },
          ],
          experiments: datasetExperiments,
        }))
      : flattenGroups
          .filter(
            (group) =>
              group.level === groups.length - 1 &&
              isGroupFullyExpanded(
                group,
                expandingConfig.expanded as Record<string, boolean>,
              ),
          )
          .map((group) => {
            const groupExperiments = experiments.filter((experiment) => {
              return groupFieldNames.every(
                (fieldName) =>
                  experiment[fieldName] === group.rowGroupData[fieldName],
              );
            });
            return {
              id: group.id,
              name: group.metadataPath.map((metadataPath, index) => {
                const label = get(
                  groupExperiments[0],
                  `${metadataPath}.label`.split("."),
                  undefined,
                );

                const value = get(
                  groupExperiments[0],
                  `${metadataPath}.value`.split("."),
                  undefined,
                );

                const groupField = groups[index]?.field;
                const groupLabel =
                  groupField === COLUMN_DATASET_ID
                    ? itemSourceLabel
                    : calculateGroupLabel(groups[index]);

                return {
                  label: groupLabel,
                  value:
                    label === DELETED_ENTITY_LABEL
                      ? `Deleted ${itemSourceLabel.toLowerCase()}`
                      : label || value || "Undefined",
                };
              }),
              experiments: groupExperiments,
            };
          });

    deepestExpandedGroups.forEach((group) => {
      const groupExperiments = group.experiments;

      if (groupExperiments.length === 0) return;

      const groupKey = group.id;
      if (!groupsMap[groupKey]) {
        groupsMap[groupKey] = {
          id: groupKey,
          name: group.name,
          data: [],
          lines: [],
          labelsMap: {},
        };
      }

      groupExperiments.forEach((experiment) => {
        const scores: Record<string, number> = {};
        if (isTestSuiteTab) {
          if (isNumber(experiment.pass_rate)) {
            scores[PASS_RATE_LABEL] = experiment.pass_rate;
          }
        } else {
          (experiment.feedback_scores ?? []).forEach((s) => {
            scores[s.name] = s.value;
          });
          (experiment.experiment_scores ?? []).forEach((s) => {
            scores[s.name] = s.value;
          });
        }

        groupsMap[groupKey].data.unshift({
          entityId: experiment.id,
          entityName: experiment.name,
          createdDate: formatDate(experiment.created_at),
          scores,
        });

        groupsMap[groupKey].lines = uniq([
          ...groupsMap[groupKey].lines,
          ...Object.keys(scores),
        ]);

        (experiment.feedback_scores || []).forEach((score) => {
          groupsMap[groupKey].labelsMap![score.name] = getScoreDisplayName(
            score.name,
            SCORE_TYPE_FEEDBACK,
          );
        });
      });
    });

    return Object.values(groupsMap);
  }, [
    hasGroups,
    itemSourceLabel,
    experiments,
    flattenGroups,
    groups,
    expandingConfig.expanded,
    groupFieldNames,
    isTestSuiteTab,
  ]);

  const isTableLoading =
    isExistencePending ||
    isPending ||
    isFeedbackScoresPending ||
    (isPlaceholderData && experiments.length === 0);

  return (
    <>
      {(isTableLoading || experiments.length > 0) && (
        <PageBodyStickyContainer
          direction="horizontal"
          className="-mb-2 pt-4"
          limitWidth
        >
          {isTableLoading ? (
            <Skeleton className="h-[208px] w-full min-w-[400px] rounded-md" />
          ) : (
            <FeedbackScoresChartsWrapper
              chartsData={chartsData}
              noDataComponent={
                <Card className="flex min-h-[208px] w-full min-w-[400px] flex-col items-center justify-center gap-2">
                  <ChartLine className="size-4 shrink-0 text-light-slate" />
                  <div className="comet-body-s-accented text-foreground">
                    No charts to show
                  </div>
                  <div className="comet-body-s text-muted-slate">
                    Please expand a group to see its chart. You can expand up to{" "}
                    {MAX_EXPANDED_DEEPEST_GROUPS} deepest groups simultaneously.
                  </div>
                </Card>
              }
              areAggregatedScores
              legendPosition={isTestSuiteTab ? "bottom" : "right"}
            />
          )}
        </PageBodyStickyContainer>
      )}
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder="Search"
            className="w-[200px]"
            dimension="sm"
          ></SearchInput>
          <GroupsButton
            columns={filterAndGroupColumns}
            config={filtersAndGroupsConfig as never}
            groups={groups}
            onChange={setGroups}
          />
          <FiltersButton
            columns={filterAndGroupColumns}
            config={filtersAndGroupsConfig as never}
            filters={filters}
            onChange={setFilters}
            layout="add"
          />
        </div>
        <div className="flex items-center gap-2">
          <ExperimentsActionsPanel
            experiments={selectedRows}
            onTagsAdded={() => {
              setRowSelection({});
            }}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <ColumnsButton
            columns={availableColumns}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
            layout="labeled"
          ></ColumnsButton>
          <RefreshButton
            tooltip="Refresh experiments list"
            isFetching={isFetching}
            onRefresh={() => {
              refetch();
              refetchPinned();
            }}
          />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        key={hasGroups ? "grouped" : "ungrouped"}
        columns={columns}
        aggregationMap={aggregationMap}
        data={experiments}
        onRowClick={handleRowClick}
        renderCustomRow={renderCustomRowCallback}
        getIsCustomRow={getIsGroupRow}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        pinningConfig={pinningConfig}
        expandingConfig={expandingConfig}
        groupingConfig={groupingConfig}
        getRowId={getExperimentRowId}
        getRowClassName={getExperimentRowClassName}
        columnPinning={columnPinningConfig}
        noData={
          <DataTableNoMatchingData
            onClearFilters={
              search || filters.length > 0 ? handleClearFilters : undefined
            }
          />
        }
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
        stickyHeader
        showSkeleton={isTableLoading}
        showLoadingOverlay={!isTableLoading && isPlaceholderData && isFetching}
      />
      <PageBodyStickyContainer
        className="py-4"
        direction="horizontal"
        limitWidth
      >
        {!hasGroups && (
          <DataTablePagination
            page={page!}
            pageChange={setPage}
            size={size!}
            sizeChange={setSize}
            total={total}
          ></DataTablePagination>
        )}
      </PageBodyStickyContainer>
    </>
  );
};

export default GeneralDatasetsTab;
