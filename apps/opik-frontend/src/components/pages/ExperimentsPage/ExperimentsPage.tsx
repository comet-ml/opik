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
import { useTranslation } from "react-i18next";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
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
  useFilterAndGroupColumns,
  useExperimentsGroupsAndFilters,
} from "@/components/pages-shared/experiments/useExperimentsGroupsAndFilters";
import { useExperimentsFeedbackScores } from "@/components/pages-shared/experiments/useExperimentsFeedbackScores";
import { useExperimentsAutoExpandingLogic } from "@/components/pages-shared/experiments/useExperimentsAutoExpandingLogic";
import { useExpandingConfig } from "@/components/pages-shared/experiments/useExpandingConfig";
import {
  getIsGroupRow,
  getRowId,
  renderCustomRow,
} from "@/components/shared/DataTable/utils";
import { calculateGroupLabel, isGroupFullyExpanded } from "@/lib/groups";
import MultiResourceCell from "@/components/shared/DataTableCells/MultiResourceCell";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import { formatNumericData } from "@/lib/utils";
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

const STORAGE_KEY_PREFIX = "experiments";
const PAGINATION_SIZE_KEY = "experiments-pagination-size";
const COLUMNS_SORT_KEY = "experiments-columns-sort";

const useExperimentsColumns = () => {
  const { t, i18n } = useTranslation();
  
  return useMemo<ColumnData<GroupedExperiment>[]>(() => [
    {
      id: COLUMN_ID_ID,
      label: t("experiments.columns.id"),
      type: COLUMN_TYPE.string,
      cell: IdCell as never,
    },
    {
      id: COLUMN_DATASET_ID,
      label: t("experiments.columns.dataset"),
      type: COLUMN_TYPE.string,
      cell: ResourceCell as never,
      customMeta: {
        nameKey: "dataset_name",
        idKey: "dataset_id",
        resource: RESOURCE_TYPE.dataset,
      },
    },
    {
      id: "created_at",
      label: t("experiments.columns.createdAt"),
      type: COLUMN_TYPE.time,
      accessorFn: (row) => formatDate(row.created_at),
    },
    {
      id: "created_by",
      label: t("experiments.columns.createdBy"),
      type: COLUMN_TYPE.string,
    },
    {
      id: "duration.p50",
      label: t("projects.columns.duration"),
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
      label: t("projects.columns.durationP90"),
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
      label: t("projects.columns.durationP99"),
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
      label: t("prompts.commit"),
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
    label: t("experiments.columns.traceCount"),
    type: COLUMN_TYPE.number,
    aggregatedCell: TextCell.Aggregation as never,
    customMeta: {
      aggregationKey: "trace_count",
    },
  },
  {
    id: "total_estimated_cost",
    label: t("experiments.columns.totalEstimatedCost"),
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
    aggregatedCell: CostCell.Aggregation as never,
    customMeta: {
      aggregationKey: "total_estimated_cost",
    },
  },
  {
    id: "total_estimated_cost_avg",
    label: t("experiments.columns.costPerTrace"),
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
    aggregatedCell: CostCell.Aggregation as never,
    customMeta: {
      aggregationKey: "total_estimated_cost_avg",
    },
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: t("experiments.columns.feedbackScores"),
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) =>
      get(row, "feedback_scores", []).map((score) => ({
        ...score,
        value: formatNumericData(score.value),
      })),
    cell: FeedbackScoreListCell as never,
    aggregatedCell: FeedbackScoreListCell.Aggregation as never,
    customMeta: {
      getHoverCardName: (row: GroupedExperiment) => row.name,
      isAverageScores: true,
      aggregationKey: "feedback_scores",
    },
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.what_are_feedback_scores],
  },
  {
    id: COLUMN_COMMENTS_ID,
    label: t("experiments.columns.comments"),
    type: COLUMN_TYPE.string,
    cell: CommentsCell as never,
  },
    {
      id: COLUMN_METADATA_ID,
      label: t("configuration.title"),
      type: COLUMN_TYPE.dictionary,
      accessorFn: (row) =>
        isObject(row.metadata)
          ? JSON.stringify(row.metadata, null, 2)
          : row.metadata,
      cell: CodeCell as never,
    },
  ], [t, i18n.language]);
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "created_at",
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_COMMENTS_ID,
];

export const MAX_EXPANDED_DEEPEST_GROUPS = 5;

const ExperimentsPage: React.FC = () => {
  const { t } = useTranslation();
  const DEFAULT_COLUMNS = useExperimentsColumns();
  const FILTER_AND_GROUP_COLUMNS = useFilterAndGroupColumns();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [query] = useQueryParam("new", JsonParam);

  const [openDialog, setOpenDialog] = useState<boolean>(
    Boolean(query ? query.experiment : false),
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
    ? t("experiments.noExperiments")
    : t("experiments.noSearchResults");

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
  } = useExperimentsTableConfig({
    storageKeyPrefix: STORAGE_KEY_PREFIX,
    defaultColumns: DEFAULT_COLUMNS,
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

  const chartsData = useMemo(() => {
    const groupsMap: Record<string, ChartData> = {};

    // Handle no grouping case - create a single group with all visible experiments
    const deepestExpandedGroups = !hasGroups
      ? [
          {
            id: "visible_experiments",
            name: "Visible experiments",
            experiments,
          },
        ]
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
      groupExperiments.forEach((experiment) => {
        groupsMap[groupKey].data.unshift({
          entityId: experiment.id,
          entityName: experiment.name,
          createdDate: formatDate(experiment.created_at),
          scores: (experiment.feedback_scores || []).reduce<
            Record<string, number>
          >((acc, score) => {
            acc[score.name] = score.value;
            return acc;
          }, {}),
        });

        groupsMap[groupKey].lines = uniq([
          ...groupsMap[groupKey].lines,
          ...(experiment.feedback_scores || []).map((s) => s.name),
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
          <h1 className="comet-title-l truncate break-words">{t("experiments.title")}</h1>
        </div>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerDescription
          {...EXPLAINERS_MAP[EXPLAINER_ID.whats_an_experiment]}
          description={t("experiments.explainer.description")}
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
                  {t("experiments.charts.noChartsTitle")}
                </div>
                <div className="comet-body-s text-muted-slate">
                  {t("experiments.charts.noChartsMessage", { count: MAX_EXPANDED_DEEPEST_GROUPS })}
                </div>
              </Card>
            }
            isAverageScores
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
            placeholder={t("experiments.searchByName")}
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
          <TooltipWrapper content={t("experiments.refreshList")}>
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
            columns={DEFAULT_COLUMNS}
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
            {t("experiments.createNew")}
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
        getRowId={getRowId}
        columnPinning={columnPinningConfig}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewExperimentClick}>
                {t("experiments.createNew")}
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
