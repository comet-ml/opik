import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { RotateCw } from "lucide-react";
import findIndex from "lodash/findIndex";
import isNumber from "lodash/isNumber";
import get from "lodash/get";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
} from "@/components/pages-shared/traces/MetricDateRangeSelect";

import {
  COLUMN_COMMENTS_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  COLUMN_USAGE_ID,
  ColumnData,
  ColumnsStatistic,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { Thread } from "@/types/traces";
import {
  convertColumnDataToColumn,
  injectColumnCallback,
  migrateColumnsOrder,
  migrateSelectedColumns,
} from "@/lib/table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import NoThreadsPage from "@/components/pages/TracesPage/ThreadsTab/NoThreadsPage";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import PrettyCell from "@/components/shared/DataTableCells/PrettyCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ThreadDetailsPanel from "@/components/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import ThreadsActionsPanel from "@/components/pages/TracesPage/ThreadsTab/ThreadsActionsPanel";
import useThreadList from "@/api/traces/useThreadsList";
import useThreadsStatistic from "@/api/traces/useThreadsStatistic";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import DataTableStateHandler from "@/components/shared/DataTableStateHandler/DataTableStateHandler";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";
import ThreadsFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/ThreadsFeedbackScoresSelect";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import {
  USER_FEEDBACK_COLUMN_ID,
  USER_FEEDBACK_NAME,
} from "@/constants/shared";
import { useTruncationEnabled } from "@/components/server-sync-provider";
import LogsTypeToggle from "@/components/pages/TracesPage/LogsTab/LogsTypeToggle";
import { LOGS_TYPE } from "@/constants/traces";

const getRowId = (d: Thread) => d.id;

const REFETCH_INTERVAL = 30000;

const SHARED_COLUMNS: ColumnData<Thread>[] = [
  {
    id: "first_message",
    label: "First message",
    size: 400,
    type: COLUMN_TYPE.string,
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "input",
    },
  },
  {
    id: "last_message",
    label: "Last message",
    size: 400,
    type: COLUMN_TYPE.string,
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "output",
    },
  },
  {
    id: "number_of_messages",
    label: "Message count",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      isNumber(row.number_of_messages) ? `${row.number_of_messages}` : "-",
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
    sortable: true,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
    cell: DurationCell as never,
    statisticDataFormater: formatDuration,
    statisticTooltipFormater: formatDuration,
    supportsPercentiles: true,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    iconType: "tags",
    cell: ListCell as never,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.start_time),
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.end_time),
  },
];

const DEFAULT_COLUMNS: ColumnData<Thread>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
    sortable: true,
  },
  ...SHARED_COLUMNS,
  {
    id: `${COLUMN_USAGE_ID}.total_tokens`,
    label: "Total tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.total_tokens)
        ? `${row.usage.total_tokens}`
        : "-",
    statisticKey: "usage.total_tokens",
  },
  {
    id: `${COLUMN_USAGE_ID}.prompt_tokens`,
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.prompt_tokens)
        ? `${row.usage.prompt_tokens}`
        : "-",
    statisticKey: "usage.prompt_tokens",
  },
  {
    id: `${COLUMN_USAGE_ID}.completion_tokens`,
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.completion_tokens)
        ? `${row.usage.completion_tokens}`
        : "-",
    statisticKey: "usage.completion_tokens",
  },
  {
    id: "total_estimated_cost",
    label: "Estimated cost",
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.hows_the_thread_cost_estimated],
    size: 160,
    statisticKey: "total_estimated_cost",
    statisticDataFormater: formatCost,
    statisticTooltipFormater: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_COMMENTS_ID,
    label: "Comments",
    type: COLUMN_TYPE.string,
    cell: CommentsCell as never,
  },
];

const FILTER_COLUMNS: ColumnData<Thread>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS,
  {
    id: "annotation_queue_ids",
    label: "Annotation queue ID",
    type: COLUMN_TYPE.list,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "start_time",
  "first_message",
  "last_message",
  "number_of_messages",
  "duration",
  `${COLUMN_USAGE_ID}.total_tokens`,
  "total_estimated_cost",
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_COMMENTS_ID,
];

const DEFAULT_THREADS_ORDER: string[] = [
  "start_time",
  "first_message",
  "last_message",
  "number_of_messages",
  "duration",
  `${COLUMN_USAGE_ID}.total_tokens`,
  "total_estimated_cost",
  COLUMN_COMMENTS_ID,
];

const SELECTED_COLUMNS_KEY = "threads-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "threads-columns-width";
const COLUMNS_ORDER_KEY = "threads-columns-order";
const COLUMNS_ORDER_V2_KEY = `${COLUMNS_ORDER_KEY}-v2`;
const COLUMNS_SORT_KEY = "threads-columns-sort";
const COLUMNS_SCORES_ORDER_KEY = "threads-columns-scores-order";
const PAGINATION_SIZE_KEY = "threads-pagination-size";
const ROW_HEIGHT_KEY = "threads-row-height";

type ThreadsTabProps = {
  projectId: string;
  projectName: string;
  logsType: LOGS_TYPE;
  onLogsTypeChange: (type: LOGS_TYPE) => void;
};

export const ThreadsTab: React.FC<ThreadsTabProps> = ({
  projectId,
  projectName,
  logsType,
  onLogsTypeChange,
}) => {
  const truncationEnabled = useTruncationEnabled();

  const {
    dateRange,
    handleDateRangeChange,
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQueryAndStorage();
  const [search = "", setSearch] = useQueryParam(
    "threads_search",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const { data: feedbackScoresNames, isPending: isFeedbackScoresNamesPending } =
    useThreadsFeedbackScoresNames({
      projectId,
    });

  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const [threadId = "", setThreadId] = useQueryParam("thread", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("threads_page", NumberParam, {
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

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: ROW_HEIGHT_KEY,
    queryKey: "threads_height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [filters = [], setFilters] = useQueryParam(
    `threads_filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: COLUMNS_SORT_KEY,
    queryKey: `threads_sorting`,
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(COLUMNS_SCORES_ORDER_KEY, {
    defaultValue: [],
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [isTableDataEnabled, setIsTableDataEnabled] = useState(false);

  // Enable table data loading after initial render to allow users to change the date filter
  React.useEffect(() => {
    const timer = setTimeout(() => {
      setIsTableDataEnabled(true);
    }, 0);
    return () => clearTimeout(timer);
  }, []);

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useThreadList(
      {
        projectId,
        sorting: sortedColumns,
        filters,
        page: page as number,
        size: size as number,
        search: search as string,
        truncate: truncationEnabled,
        fromTime: intervalStart,
        toTime: intervalEnd,
      },
      {
        enabled: isTableDataEnabled,
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
        refetchOnMount: false,
      },
    );

  const { refetch: refetchExportData } = useThreadList(
    {
      projectId,
      sorting: sortedColumns,
      filters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: false,
      fromTime: intervalStart,
      toTime: intervalEnd,
    },
    {
      enabled: false,
      refetchOnMount: "always",
    },
  );

  const { data: statisticData } = useThreadsStatistic(
    {
      projectId,
      filters,
      search: search as string,
      fromTime: intervalStart,
      toTime: intervalEnd,
    },
    {
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const noData = !search && filters.length === 0;
  const noDataText = noData ? `There are no threads yet` : "No search results";

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: ThreadsFeedbackScoresSelect,
          keyComponentProps: {
            projectId,
            placeholder: "Select score",
          },
        },
      },
    }),
    [projectId],
  );

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresNames?.scores ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${c.name}`,
        label: c.name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [feedbackScoresNames]);

  const scoresColumnsData = useMemo(() => {
    // Always include "User feedback" column, even if it has no data
    const userFeedbackColumn: DynamicColumn = {
      id: USER_FEEDBACK_COLUMN_ID,
      label: USER_FEEDBACK_NAME,
      columnType: COLUMN_TYPE.number,
    };

    // Filter out "User feedback" from dynamic columns to avoid duplicates
    const otherDynamicColumns = dynamicScoresColumns.filter(
      (col) => col.id !== USER_FEEDBACK_COLUMN_ID,
    );

    return [userFeedbackColumn, ...otherDynamicColumns].map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          header: FeedbackScoreHeader as never,
          cell: FeedbackScoreCell as never,
          accessorFn: (row) =>
            row.feedback_scores?.find((f) => f.name === label),
          statisticKey: `${COLUMN_FEEDBACK_SCORES_ID}.${label}`,
          statisticDataFormater: formatScoreDisplay,
        }) as ColumnData<Thread>,
    );
  }, [dynamicScoresColumns]);

  const rows: Thread[] = useMemo(() => data?.content ?? [], [data]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const columnsStatistic: ColumnsStatistic = useMemo(
    () => statisticData?.stats ?? [],
    [statisticData?.stats],
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_ID_ID, "start_time"],
      ),
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_V2_KEY,
    {
      defaultValue: migrateColumnsOrder(
        COLUMNS_ORDER_KEY,
        DEFAULT_THREADS_ORDER,
      ),
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const selectedRows: Thread[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const getDataForExport = useCallback(async (): Promise<Thread[]> => {
    const result = await refetchExportData();

    if (result.error) {
      throw result.error;
    }

    if (!result.data?.content) {
      throw new Error("Failed to fetch data");
    }

    const allRows = result.data.content;
    const selectedIds = Object.keys(rowSelection);

    return allRows.filter((row) => selectedIds.includes(row.id));
  }, [refetchExportData, rowSelection]);

  const handleRowClick = useCallback(
    (row?: Thread) => {
      if (!row) return;
      setThreadId(row.id);
    },
    [setThreadId],
  );

  const meta = useMemo(
    () => ({
      projectId,
      projectName,
      enableUserFeedbackEditing: true,
    }),
    [projectId, projectName],
  );

  const columns = useMemo(() => {
    const convertedColumns = convertColumnDataToColumn<Thread, Thread>(
      DEFAULT_COLUMNS,
      {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      },
    );

    return [
      generateSelectColumDef<Thread>(),
      ...injectColumnCallback(convertedColumns, COLUMN_ID_ID, handleRowClick),
      ...convertColumnDataToColumn<Thread, Thread>(scoresColumnsData, {
        columnsOrder: scoresColumnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
    ];
  }, [
    sortableBy,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
    handleRowClick,
  ]);

  const columnsToExport = useMemo(() => {
    return columns
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) =>
        c === COLUMN_SELECT_ID
          ? false
          : selectedColumns.includes(c) ||
            (DEFAULT_COLUMN_PINNING.left || []).includes(c),
      );
  }, [columns, selectedColumns]);

  const activeRowId = threadId;
  const rowIndex = findIndex(rows, (row) => activeRowId === row.id);

  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  const handleRowChange = useCallback(
    (shift: number) => handleRowClick(rows[rowIndex + shift]),
    [handleRowClick, rowIndex, rows],
  );

  const handleClose = useCallback(() => {
    setThreadId("");
    setTraceId("");
    setSpanId("");
  }, [setSpanId, setTraceId, setThreadId]);

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

  const isTableLoading = isPending || isFeedbackScoresNamesPending;
  const showEmptyState =
    !isTableLoading && noData && rows.length === 0 && page === 1;

  return (
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 py-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <LogsTypeToggle value={logsType} onValueChange={onLogsTypeChange} />
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by ID"
            className="w-[320px]"
            dimension="sm"
          ></SearchInput>
          <FiltersButton
            columns={FILTER_COLUMNS}
            filters={filters}
            onChange={setFilters}
            config={filtersConfig as never}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          <ThreadsActionsPanel
            projectId={projectId}
            projectName={projectName}
            getDataForExport={getDataForExport}
            selectedRows={selectedRows}
            columnsToExport={columnsToExport}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
          />
          <TooltipWrapper content={`Refresh threads list`}>
            <Button
              variant="outline"
              size="icon-sm"
              className="shrink-0"
              onClick={() => {
                refetch();
              }}
            >
              <RotateCw />
            </Button>
          </TooltipWrapper>
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>

      <DataTableStateHandler
        isLoading={isTableLoading}
        isEmpty={showEmptyState}
        emptyState={<NoThreadsPage />}
      >
        <DataTable
          columns={columns}
          columnsStatistic={columnsStatistic}
          data={rows}
          onRowClick={handleRowClick}
          activeRowId={activeRowId ?? ""}
          sortConfig={sortConfig}
          resizeConfig={resizeConfig}
          selectionConfig={{
            rowSelection,
            setRowSelection,
          }}
          getRowId={getRowId}
          rowHeight={height as ROW_HEIGHT}
          columnPinning={DEFAULT_COLUMN_PINNING}
          noData={<DataTableNoData title={noDataText} />}
          TableWrapper={PageBodyStickyTableWrapper}
          stickyHeader
          meta={meta}
          showLoadingOverlay={isPlaceholderData && isFetching}
        />
        <PageBodyStickyContainer
          className="py-4"
          direction="horizontal"
          limitWidth
        >
          <DataTablePagination
            page={page as number}
            pageChange={setPage}
            size={size as number}
            sizeChange={setSize}
            total={data?.total ?? 0}
            supportsTruncation
            truncationEnabled={truncationEnabled}
          />
        </PageBodyStickyContainer>
      </DataTableStateHandler>
      <TraceDetailsPanel
        projectId={projectId}
        traceId={traceId!}
        spanId={spanId!}
        setSpanId={setSpanId}
        setThreadId={setThreadId}
        open={Boolean(traceId) && !threadId}
        onClose={handleClose}
      />
      <ThreadDetailsPanel
        projectId={projectId}
        projectName={projectName}
        traceId={traceId!}
        setTraceId={setTraceId}
        threadId={threadId!}
        open={Boolean(threadId)}
        onClose={handleClose}
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        onRowChange={handleRowChange}
      />
    </>
  );
};

export default ThreadsTab;
