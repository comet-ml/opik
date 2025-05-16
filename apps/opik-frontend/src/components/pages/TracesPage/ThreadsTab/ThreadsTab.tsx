import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import { RotateCw } from "lucide-react";
import findIndex from "lodash/findIndex";
import isNumber from "lodash/isNumber";
import get from "lodash/get";

import {
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ROW_HEIGHT,
} from "@/types/shared";
import { Thread } from "@/types/traces";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
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
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import PrettyCell from "@/components/shared/DataTableCells/PrettyCell";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ThreadDetailsPanel from "@/components/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import { formatDate } from "@/lib/date";
import ThreadsActionsPanel from "@/components/pages/TracesPage/ThreadsTab/ThreadsActionsPanel";
import useThreadList from "@/api/traces/useThreadsList";

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
    label: "No. of messages",
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
  },
];

const DEFAULT_COLUMNS: ColumnData<Thread>[] = [
  ...SHARED_COLUMNS,
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
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

const FILTER_COLUMNS: ColumnData<Thread>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS,
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "first_message",
  "last_message",
  "number_of_messages",
  "created_at",
  "last_updated_at",
  "duration",
];

const SELECTED_COLUMNS_KEY = "threads-selected-columns";
const COLUMNS_WIDTH_KEY = "threads-columns-width";
const COLUMNS_ORDER_KEY = "threads-columns-order";
const PAGINATION_SIZE_KEY = "threads-pagination-size";
const ROW_HEIGHT_KEY = "threads-row-height";

type ThreadsTabProps = {
  projectId: string;
  projectName: string;
};

export const ThreadsTab: React.FC<ThreadsTabProps> = ({
  projectId,
  projectName,
}) => {
  const [search = "", setSearch] = useQueryParam(
    "threads_search",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

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

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending, refetch } = useThreadList(
    {
      projectId,
      filters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: true,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const noData = !search && filters.length === 0;
  const noDataText = noData ? `There are no threads yet` : "No search results";

  const rows: Thread[] = useMemo(() => data?.content ?? [], [data]);

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

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const selectedRows: Thread[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleRowClick = useCallback(
    (row?: Thread) => {
      if (!row) return;
      setThreadId(row.id);
    },
    [setThreadId],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Thread>(),
      mapColumnDataFields<Thread, Thread>({
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: LinkCell as never,
        customMeta: {
          callback: handleRowClick,
          asId: true,
        },
      }),
      ...convertColumnDataToColumn<Thread, Thread>(DEFAULT_COLUMNS, {
        columnsOrder,
        selectedColumns,
      }),
    ];
  }, [handleRowClick, columnsOrder, selectedColumns]);

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

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return <NoThreadsPage />;
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
          />
        </div>
        <div className="flex items-center gap-2">
          <ThreadsActionsPanel
            projectId={projectId}
            projectName={projectName}
            rows={selectedRows}
            columnsToExport={columnsToExport}
          />
          <Separator orientation="vertical" className="mx-1 h-4" />
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
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
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
        ></DataTablePagination>
      </PageBodyStickyContainer>
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
