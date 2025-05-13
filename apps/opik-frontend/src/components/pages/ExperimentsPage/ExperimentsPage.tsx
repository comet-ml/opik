import React, { useCallback, useMemo, useRef, useState } from "react";
import { Info, RotateCw } from "lucide-react";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnPinningState,
  ColumnSort,
  Row,
  RowSelectionState,
} from "@tanstack/react-table";
import { useNavigate } from "@tanstack/react-router";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import get from "lodash/get";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { formatDate } from "@/lib/date";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
} from "@/types/shared";
import { convertColumnDataToColumn, isColumnSortable } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import AddExperimentDialog from "@/components/pages-shared/experiments/AddExperimentDialog/AddExperimentDialog";
import ExperimentsActionsPanel from "@/components/pages-shared/experiments/ExperimentsActionsPanel/ExperimentsActionsPanel";
import ExperimentsFiltersButton from "@/components/pages-shared/experiments/ExperimentsFiltersButton/ExperimentsFiltersButton";
import ExperimentRowActionsCell from "@/components/pages/ExperimentsPage/ExperimentRowActionsCell";
import FeedbackScoresChartsWrapper from "@/components/pages-shared/experiments/FeedbackScoresChartsWrapper/FeedbackScoresChartsWrapper";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import useGroupedExperimentsList, {
  GroupedExperiment,
} from "@/hooks/useGroupedExperimentsList";
import {
  checkIsMoreRowId,
  generateGroupedNameColumDef,
  generateGroupedCellDef,
  getIsCustomRow,
  getRowId,
  getSharedShiftCheckboxClickHandler,
  GROUPING_CONFIG,
  renderCustomRow,
} from "@/components/pages-shared/experiments/table";
import { DEFAULT_GROUPS_PER_PAGE, GROUPING_COLUMN } from "@/constants/grouping";
import { useExpandingConfig } from "@/components/pages-shared/experiments/useExpandingConfig";
import { generateActionsColumDef } from "@/components/shared/DataTable/utils";
import MultiResourceCell from "@/components/shared/DataTableCells/MultiResourceCell";
import FeedbackScoreListCell from "@/components/shared/DataTableCells/FeedbackScoreListCell";
import { formatNumericData } from "@/lib/utils";

const SELECTED_COLUMNS_KEY = "experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "experiments-columns-width";
const COLUMNS_ORDER_KEY = "experiments-columns-order";
const COLUMNS_SORT_KEY = "experiments-columns-sort";
const COLUMNS_SCORES_ORDER_KEY = "experiments-scores-columns-order";

export const DEFAULT_COLUMNS: ColumnData<GroupedExperiment>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
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
  },
  {
    id: "trace_count",
    label: "Trace count",
    type: COLUMN_TYPE.number,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) =>
      get(row, "feedback_scores", []).map((score) => ({
        ...score,
        value: formatNumericData(score.value),
      })),
    cell: FeedbackScoreListCell as never,
    customMeta: {
      getHoverCardName: (row: GroupedExperiment) => row.name,
      isAverageScores: true,
    },
  },
  {
    id: COLUMN_COMMENTS_ID,
    label: "Comments",
    type: COLUMN_TYPE.string,
    cell: CommentsCell as never,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_NAME_ID, GROUPING_COLUMN],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  "created_at",
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_COMMENTS_ID,
];

const ExperimentsPage: React.FunctionComponent = () => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const navigate = useNavigate();
  const resetDialogKeyRef = useRef(0);
  const [openDialog, setOpenDialog] = useState<boolean>(false);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [datasetId = "", setDatasetId] = useQueryParam("dataset", StringParam, {
    updateType: "replaceIn",
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

  const { checkboxClickHandler } = useMemo(() => {
    return {
      checkboxClickHandler: getSharedShiftCheckboxClickHandler(),
    };
  }, []);

  const { data, isPending, refetch, datasetsData } = useGroupedExperimentsList({
    workspaceName,
    groupLimit,
    datasetId: datasetId!,
    sorting: sortedColumns,
    search: search!,
    page: page!,
    size: DEFAULT_GROUPS_PER_PAGE,
    polling: true,
  });

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useExperimentsFeedbackScoresNames(
      {},
      {
        placeholderData: keepPreviousData,
        refetchInterval: 30000,
      },
    );

  const experiments = useMemo(() => data?.content ?? [], [data?.content]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const groupIds = useMemo(() => data?.groupIds ?? [], [data?.groupIds]);
  const total = data?.total ?? 0;
  const noData = !search && !datasetId;
  const noDataText = noData
    ? "There are no experiments yet"
    : "No search results";

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_SELECTED_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(COLUMNS_SCORES_ORDER_KEY, {
    defaultValue: [],
  });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${c.name}`,
        label: c.name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [feedbackScoresData?.scores]);

  const scoresColumnsData = useMemo(() => {
    return [
      ...dynamicScoresColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            header: FeedbackScoreHeader as never,
            cell: FeedbackScoreCell as never,
            accessorFn: (row) =>
              row.feedback_scores?.find((f) => f.name === label),
          }) as ColumnData<GroupedExperiment>,
      ),
    ];
  }, [dynamicScoresColumns]);

  const selectedRows: Array<GroupedExperiment> = useMemo(() => {
    return experiments.filter(
      (row) => rowSelection[row.id] && !checkIsMoreRowId(row.id),
    );
  }, [rowSelection, experiments]);

  const columns = useMemo(() => {
    return [
      generateGroupedNameColumDef<GroupedExperiment>(
        checkboxClickHandler,
        isColumnSortable(COLUMN_NAME_ID, sortableBy),
      ),
      generateGroupedCellDef<GroupedExperiment, unknown>(
        {
          id: GROUPING_COLUMN,
          label: "Dataset",
          type: COLUMN_TYPE.string,
          cell: ResourceCell as never,
          customMeta: {
            nameKey: "dataset_name",
            idKey: "dataset_id",
            resource: RESOURCE_TYPE.dataset,
          },
        },
        checkboxClickHandler,
      ),
      ...convertColumnDataToColumn<GroupedExperiment, GroupedExperiment>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      ...convertColumnDataToColumn<GroupedExperiment, GroupedExperiment>(
        scoresColumnsData,
        {
          columnsOrder: scoresColumnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      generateActionsColumDef({
        cell: ExperimentRowActionsCell,
      }),
    ];
  }, [
    checkboxClickHandler,
    sortableBy,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
  ]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

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

  const expandingConfig = useExpandingConfig({
    groupIds,
  });

  const handleNewExperimentClick = useCallback(() => {
    setOpenDialog(true);
    resetDialogKeyRef.current = resetDialogKeyRef.current + 1;
  }, []);

  const renderCustomRowCallback = useCallback(
    (row: Row<GroupedExperiment>, applyStickyWorkaround?: boolean) => {
      return renderCustomRow(row, setGroupLimit, applyStickyWorkaround);
    },
    [setGroupLimit],
  );

  const columnSections = useMemo(() => {
    return [
      {
        title: "Feedback scores",
        columns: scoresColumnsData,
        order: scoresColumnsOrder,
        onOrderChange: setScoresColumnsOrder,
      },
    ];
  }, [scoresColumnsData, scoresColumnsOrder, setScoresColumnsOrder]);

  if (isPending || isFeedbackScoresPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l truncate break-words">Experiments</h1>
      </div>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
          <ExperimentsFiltersButton
            datasetId={datasetId!}
            onChangeDatasetId={setDatasetId}
          />
        </div>
        <div className="flex items-center gap-2">
          <ExperimentsActionsPanel experiments={selectedRows} />
          <Separator orientation="vertical" className="mx-1 h-4" />
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
            <Info className="mr-2 size-3.5" />
            Create new experiment
          </Button>
        </div>
      </div>
      {Boolean(experiments.length) && (
        <FeedbackScoresChartsWrapper
          entities={experiments}
          datasetsData={datasetsData}
          isAverageScores
        />
      )}
      <DataTable
        columns={columns}
        data={experiments}
        onRowClick={handleRowClick}
        renderCustomRow={renderCustomRowCallback}
        getIsCustomRow={getIsCustomRow}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        expandingConfig={expandingConfig}
        groupingConfig={GROUPING_CONFIG}
        getRowId={getRowId}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <Button variant="link" onClick={handleNewExperimentClick}>
                Create new experiment
              </Button>
            )}
          </DataTableNoData>
        }
      />
      <div className="py-4">
        <DataTablePagination
          page={page!}
          pageChange={setPage}
          size={DEFAULT_GROUPS_PER_PAGE}
          total={total}
        ></DataTablePagination>
      </div>
      <AddExperimentDialog
        key={resetDialogKeyRef.current}
        open={openDialog}
        setOpen={setOpenDialog}
      />
    </div>
  );
};

export default ExperimentsPage;
