import React, { useCallback, useMemo, useState } from "react";
import { Row, RowSelectionState, ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import get from "lodash/get";
import isObject from "lodash/isObject";

import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import GroupsButton from "@/components/shared/GroupsButton/GroupsButton";
import ExperimentsActionsPanel from "@/components/pages-shared/experiments/ExperimentsActionsPanel/ExperimentsActionsPanel";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import TextCell from "@/components/shared/DataTableCells/TextCell";
import TraceCountCell from "@/components/shared/DataTableCells/TraceCountCell";
import DatasetVersionCell from "@/components/shared/DataTableCells/DatasetVersionCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import useAppStore from "@/store/AppStore";
import { transformExperimentScores } from "@/lib/experimentScoreUtils";
import useGroupedExperimentsList, {
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import {
  COLUMN_DATASET_ID,
  COLUMN_METADATA_ID,
  COLUMN_TYPE,
  ColumnData,
  COLUMN_ID_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_COMMENTS_ID,
} from "@/types/shared";
import { formatDate } from "@/lib/date";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import { Separator } from "@/components/ui/separator";
import MultiResourceCell from "@/components/shared/DataTableCells/MultiResourceCell";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  getIsGroupRow,
  renderCustomRow,
} from "@/components/shared/DataTable/utils";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { useExperimentsTableConfig } from "@/components/pages-shared/experiments/useExperimentsTableConfig";
import {
  FILTER_AND_GROUP_COLUMNS,
  useExperimentsGroupsAndFilters,
} from "@/components/pages-shared/experiments/useExperimentsGroupsAndFilters";
import { useExperimentsFeedbackScores } from "@/components/pages-shared/experiments/useExperimentsFeedbackScores";
import { useExperimentsAutoExpandingLogic } from "@/components/pages-shared/experiments/useExperimentsAutoExpandingLogic";
import { useExpandingConfig } from "@/components/pages-shared/experiments/useExpandingConfig";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";

const STORAGE_KEY_PREFIX = "prompt-experiments";
const PAGINATION_SIZE_KEY = "prompt-experiments-pagination-size";
const COLUMNS_SORT_KEY = "prompt-experiments-columns-sort";

export const MAX_EXPANDED_DEEPEST_GROUPS = 5;

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "prompt",
  COLUMN_DATASET_ID,
  "created_at",
  "trace_count",
];

interface ExperimentsTabProps {
  promptId: string;
}

const ExperimentsTab: React.FC<ExperimentsTabProps> = ({ promptId }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const isDatasetVersioningEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.DATASET_VERSIONING_ENABLED,
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
      promptId,
    });

  const expandingConfig = useExpandingConfig({
    groups,
    maxExpandedDeepestGroups: MAX_EXPANDED_DEEPEST_GROUPS,
  });

  const { data, isPending, isPlaceholderData } = useGroupedExperimentsList({
    workspaceName,
    groupLimit,
    promptId,
    filters,
    sorting: sortedColumns,
    groups,
    search: search!,
    page: page!,
    size: size!,
    expandedMap: expandingConfig.expanded as Record<string, boolean>,
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
    sortedColumns,
    setSortedColumns,
  });

  const total = data?.total ?? 0;
  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? "No experiments have used this prompt yet"
    : "No search results";

  const hasGroups = Boolean(groups.length);

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

  if (isPending || isFeedbackScoresPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 py-4"
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
          <ColumnsButton
            columns={availableColumns}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        aggregationMap={aggregationMap}
        data={experiments}
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
        noData={<DataTableNoData title={noDataText}></DataTableNoData>}
        TableBody={DataTableVirtualBody}
        TableWrapper={PageBodyStickyTableWrapper}
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
    </>
  );
};

export default ExperimentsTab;
