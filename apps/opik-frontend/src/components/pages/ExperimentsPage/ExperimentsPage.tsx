import React, { useCallback, useMemo, useRef, useState } from "react";
import { ChartLine, Info, RotateCw } from "lucide-react";
import { ColumnSort, Row, RowSelectionState } from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import useLocalStorageState from "use-local-storage-state";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import get from "lodash/get";
import uniq from "lodash/uniq";
import isObject from "lodash/isObject";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import TraceCountCell from "@/components/shared/DataTableCells/TraceCountCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import { transformExperimentScores } from "@/lib/experimentScoreUtils";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_DATASET_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
} from "@/types/shared";
import { DELETED_DATASET_LABEL } from "@/constants/groups";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import AddExperimentDialog from "@/components/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import ExperimentsActionsPanel from "@/components/pages-shared/experiments/ExperimentsActionsPanel/ExperimentsActionsPanel";
import ExperimentRowActionsCell from "@/components/pages/ExperimentsPage/ExperimentRowActionsCell";
import FeedbackScoresChartsWrapper from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartsWrapper";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { Card } from "@/components/ui/card";
import useGroupedExperimentsList, {
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import { useExperimentsTableConfig } from "@/components/pages-shared/experiments/useExperimentsTableConfig";
import {
  FILTER_AND_GROUP_COLUMNS,
  useExperimentsGroupsAndFilters,
} from "@/components/pages-shared/experiments/useExperimentsGroupsAndFilters";
import { useExperimentsFeedbackScores } from "@/components/pages-shared/experiments/useExperimentsFeedbackScores";
import { useExperimentsAutoExpandingLogic } from "@/components/pages-shared/experiments/useExperimentsAutoExpandingLogic";
import { useExpandingConfig } from "@/components/pages-shared/experiments/useExpandingConfig";
import {
  getIsGroupRow,
  renderCustomRow,
} from "@/components/shared/DataTable/utils";
import { calculateGroupLabel, isGroupFullyExpanded } from "@/lib/groups";
import MultiResourceCell from "@/components/shared/DataTableCells/MultiResourceCell";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import ExplainerDescription from "@/components/shared/ExplainerDescription/ExplainerDescription";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import PageBodyScrollContainer from "@/components/layout/PageBodyScrollContainer/PageBodyScrollContainer";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import { ChartData } from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartContent";
import GroupsButton from "@/components/shared/GroupsButton/GroupsButton";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import DatasetVersionCell from "@/components/shared/DataTableCells/DatasetVersionCell";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const STORAGE_KEY_PREFIX = "experiments";
const PAGINATION_SIZE_KEY = "experiments-pagination-size";
const COLUMNS_SORT_KEY = "experiments-columns-sort";

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_DATASET_ID,
  "created_at",
  "duration.p50",
  "trace_count",
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_COMMENTS_ID,
];

export const MAX_EXPANDED_DEEPEST_GROUPS = 5;

const ExperimentsPage: React.FC = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [query] = useQueryParam("new", JsonParam);
  const isDatasetVersioningEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_VERSIONING_ENABLED,
  );

  const [openDialog, setOpenDialog] = useState<boolean>(
    Boolean(query?.experiment),
  );

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: PAGINATION_SIZE_KEY,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [groupLimit, setGroupLimit] = useQueryParam<Record<string, number>>(
    "limits",
    { ...JsonParam, default: {} },
    {
      updateType: "replaceIn",
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [],
    },
  );

  const columnsDef: ColumnData<GroupedExperiment>[] = useMemo(() => {
    return [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
      },
      {
        id: COLUMN_DATASET_ID,
        label: "Dataset",
        type: COLUMN_TYPE.string,
        cell: ResourceCell as never,
        customMeta: {
          nameKey: "dataset_name",
          idKey: "dataset_id",
          resource: RESOURCE_TYPE.dataset,
        },
      },
      ...(isDatasetVersioningEnabled
        ? [
            {
              id: "dataset_version",
              label: "Dataset version",
              type: COLUMN_TYPE.string,
              iconType: "version" as const,
              accessorFn: (row: GroupedExperiment) =>
                row.dataset_version_summary?.version_name || "",
              cell: DatasetVersionCell as never,
            },
          ]
        : []),
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
        cell: DurationCell as never,
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
        cell: DurationCell as never,
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
        cell: DurationCell as never,
        aggregatedCell: DurationCell.Aggregation as never,
        customMeta: {
          aggregationKey: "duration.p99",
        },
      },
      {
        id: "prompt",
        label: "Prompt commit",
        type: COLUMN_TYPE.list,
        accessorFn: (row) => get(row, ["prompt_versions"], []),
        cell: MultiResourceCell as never,
        customMeta: {
          nameKey: "commit",
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
        cell: TraceCountCell as never,
        aggregatedCell: TextCell.Aggregation as never,
        customMeta: {
          aggregationKey: "trace_count",
          tooltip: "View experiment traces",
        },
      },
      {
        id: "total_estimated_cost",
        label: "Total estimated cost",
        type: COLUMN_TYPE.cost,
        cell: CostCell as never,
        aggregatedCell: CostCell.Aggregation as never,
        customMeta: {
          aggregationKey: "total_estimated_cost",
        },
      },
      {
        id: "total_estimated_cost_avg",
        label: "Cost per trace (avg.)",
        type: COLUMN_TYPE.cost,
        cell: CostCell as never,
        aggregatedCell: CostCell.Aggregation as never,
        customMeta: {
          aggregationKey: "total_estimated_cost_avg",
        },
      },
      {
        id: COLUMN_FEEDBACK_SCORES_ID,
        label: "Feedback Scores",
        type: COLUMN_TYPE.numberDictionary,
        accessorFn: transformExperimentScores,
        cell: FeedbackScoreListCell as never,
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
  }, [isDatasetVersioningEnabled]);

  const { isFeedbackScoresPending, dynamicScoresColumns } =
    useExperimentsFeedbackScores();

  const { groups, setGroups, filtersAndGroupsConfig } =
    useExperimentsGroupsAndFilters({
      storageKeyPrefix: STORAGE_KEY_PREFIX,
      sortedColumns,
      filters,
    });

  const expandingConfig = useExpandingConfig({
    groups,
    maxExpandedDeepestGroups: MAX_EXPANDED_DEEPEST_GROUPS,
  });

  const { data, isPending, isPlaceholderData, refetch } =
    useGroupedExperimentsList({
      workspaceName,
      groupLimit,
      filters,
      sorting: sortedColumns,
      groups: groups,
      search: search!,
      page: page!,
      size: size!,
      expandedMap: expandingConfig.expanded as Record<string, boolean>,
      polling: true,
    });

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const flattenGroups = useMemo(
    () => data?.flattenGroups ?? [],
    [data?.flattenGroups],
  );

  const aggregationMap = useMemo(() => {
    return data?.aggregationMap ?? {};
  }, [data?.aggregationMap]);

  useExperimentsAutoExpandingLogic({
    groups,
    flattenGroups,
    isPending,
    isPlaceholderData,
    maxExpandedDeepestGroups: MAX_EXPANDED_DEEPEST_GROUPS,
    setExpanded: expandingConfig.setExpanded,
  });

  const total = data?.total ?? 0;
  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? "There are no experiments yet"
    : "No search results";

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
  } = useExperimentsTableConfig({
    storageKeyPrefix: STORAGE_KEY_PREFIX,
    defaultColumns: columnsDef,
    defaultSelectedColumns: DEFAULT_SELECTED_COLUMNS,
    groups,
    sortableBy,
    dynamicScoresColumns,
    experiments,
    rowSelection,
    actionsCell: ExperimentRowActionsCell,
    sortedColumns,
    setSortedColumns,
  });

  const handleRowClick = useCallback(
    (row: GroupedExperiment) => {
      navigate({
        to: "/$workspaceName/experiments/$datasetId/compare",
        params: {
          datasetId: row.dataset_id,
          workspaceName,
        },
        search: {
          experiments: [row.id],
        },
      });
    },
    [navigate, workspaceName],
  );

  const hasGroups = Boolean(groups.length);

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const renderCustomRowCallback = useCallback(
    (row: Row<GroupedExperiment>) => {
      return renderCustomRow(row, setGroupLimit);
    },
    [setGroupLimit],
  );

  // Filter out dataset column when grouping by dataset
  const availableColumns = useMemo(() => {
    const isGroupingByDataset = groups.some(
      (g) => g.field === COLUMN_DATASET_ID,
    );
    if (isGroupingByDataset) {
      return columnsDef.filter((col) => col.id !== COLUMN_DATASET_ID);
    }
    return columnsDef;
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
              label: "Dataset",
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

                return {
                  label: calculateGroupLabel(groups[index]),
                  value:
                    label === DELETED_DATASET_LABEL
                      ? "Deleted dataset"
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
        };
      }

      const createScoresMap = (
        scores: Array<{ name: string; value: number }> | undefined,
        addAvgSuffix: boolean,
      ): Record<string, number> =>
        (scores || []).reduce<Record<string, number>>((acc, score) => {
          const key = addAvgSuffix ? `${score.name} (avg)` : score.name;
          acc[key] = score.value;
          return acc;
        }, {});

      const getScoreNames = (
        scores: Array<{ name: string }> | undefined,
        addAvgSuffix: boolean,
      ): string[] =>
        (scores || []).map((s) => (addAvgSuffix ? `${s.name} (avg)` : s.name));

      groupExperiments.forEach((experiment) => {
        const feedbackScoresMap = createScoresMap(
          experiment.feedback_scores,
          true,
        );
        const experimentScoresMap = createScoresMap(
          experiment.experiment_scores,
          false,
        );

        groupsMap[groupKey].data.unshift({
          entityId: experiment.id,
          entityName: experiment.name,
          createdDate: formatDate(experiment.created_at),
          scores: { ...feedbackScoresMap, ...experimentScoresMap },
        });

        const feedbackScoreNames = getScoreNames(
          experiment.feedback_scores,
          true,
        );
        const experimentScoreNames = getScoreNames(
          experiment.experiment_scores,
          false,
        );
        groupsMap[groupKey].lines = uniq([
          ...groupsMap[groupKey].lines,
          ...feedbackScoreNames,
          ...experimentScoreNames,
        ]);
      });
    });

    return Object.values(groupsMap);
  }, [
    hasGroups,
    experiments,
    flattenGroups,
    groups,
    expandingConfig.expanded,
    groupFieldNames,
  ]);

  if (isPending || isFeedbackScoresPending) {
    return <Loader />;
  }

  return (
    <PageBodyScrollContainer>
      <PageBodyStickyContainer
        className="pb-1 pt-6"
        direction="horizontal"
        limitWidth
      >
        <div className="flex items-center">
          <h1 className="comet-title-l truncate break-words">Experiments</h1>
        </div>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerDescription
          {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_experiment]}
        />
      </PageBodyStickyContainer>
      {Boolean(experiments.length) && (
        <PageBodyStickyContainer
          direction="horizontal"
          className="-mb-2 pt-4"
          limitWidth
        >
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
          />
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
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
          <FiltersButton
            columns={FILTER_AND_GROUP_COLUMNS}
            config={filtersAndGroupsConfig as never}
            filters={filters}
            onChange={setFilters}
          />
          <GroupsButton
            columns={FILTER_AND_GROUP_COLUMNS}
            config={filtersAndGroupsConfig as never}
            groups={groups}
            onChange={setGroups}
          />
        </div>
        <div className="flex items-center gap-2">
          <ExperimentsActionsPanel experiments={selectedRows} />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <TooltipWrapper content="Refresh experiments list">
            <Button
              variant="outline"
              size="icon-sm"
              className="shrink-0"
              onClick={() => refetch()}
            >
              <RotateCw />
            </Button>
          </TooltipWrapper>
          <ColumnsButton
            columns={availableColumns}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
          ></ColumnsButton>
          <Button
            variant="outline"
            size="sm"
            onClick={handleNewExperimentClick}
          >
            <Info className="mr-1.5 size-3.5" />
            Create new experiment
          </Button>
        </div>
      </PageBodyStickyContainer>
      <DataTable
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
        expandingConfig={expandingConfig}
        groupingConfig={groupingConfig}
        getRowId={getExperimentRowId}
        columnPinning={columnPinningConfig}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewExperimentClick}>
                Create new experiment
              </Button>
            )}
          </DataTableNoData>
        }
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
        stickyHeader
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
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
        datasetName={query?.datasetName}
      />
    </PageBodyScrollContainer>
  );
};

export default ExperimentsPage;
