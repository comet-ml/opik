import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { ColumnPinningState, RowSelectionState } from "@tanstack/react-table";
import { RotateCw } from "lucide-react";
import findIndex from "lodash/findIndex";
import isObject from "lodash/isObject";
import isNumber from "lodash/isNumber";
import get from "lodash/get";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import {
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ColumnsStatistic,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { BaseTraceData, Span, Trace } from "@/types/traces";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import NoTracesPage from "@/components/pages/TracesPage/NoTracesPage";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import TracesActionsPanel from "@/components/pages/TracesPage/TracesSpansTab/TracesActionsPanel";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import ErrorCell from "@/components/shared/DataTableCells/ErrorCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import { formatDate, formatDuration } from "@/lib/date";
import useTracesOrSpansStatistic from "@/hooks/useTracesOrSpansStatistic";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { DEFAULT_COLUMN_PINNING } from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/ExperimentItemsTab";

const getRowId = (d: Trace | Span) => d.id;

const REFETCH_INTERVAL = 30000;

const SHARED_COLUMNS: ColumnData<BaseTraceData>[] = [
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
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
  {
    id: "input",
    label: "Input",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.input) ? JSON.stringify(row.input, null, 2) : row.input,
    cell: CodeCell as never,
  },
  {
    id: "output",
    label: "Output",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.output) ? JSON.stringify(row.output, null, 2) : row.output,
    cell: CodeCell as never,
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
    cell: DurationCell as never,
    statisticDataFormater: formatDuration,
  },
  {
    id: "metadata",
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.metadata)
        ? JSON.stringify(row.metadata, null, 2)
        : row.metadata,
    cell: CodeCell as never,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
  },
  {
    id: "usage.total_tokens",
    label: "Total tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.total_tokens)
        ? `${row.usage.total_tokens}`
        : "-",
  },
  {
    id: "usage.prompt_tokens",
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.prompt_tokens)
        ? `${row.usage.prompt_tokens}`
        : "-",
  },
  {
    id: "usage.completion_tokens",
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) =>
      row.usage && isNumber(row.usage.completion_tokens)
        ? `${row.usage.completion_tokens}`
        : "-",
  },
  {
    id: "total_estimated_cost",
    label: "Estimated cost",
    type: COLUMN_TYPE.cost,
    cell: CostCell as never,
  },
];

const TRACES_PAGE_COLUMNS = [
  ...SHARED_COLUMNS,
  {
    id: "error_info",
    label: "Error",
    type: COLUMN_TYPE.string,
    cell: ErrorCell as never,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

export const TRACES_PAGE_FILTERS_COLUMNS = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },

  {
    id: "feedback_scores",
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  ...SHARED_COLUMNS,
];

const DEFAULT_TRACES_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

const DEFAULT_TRACES_PAGE_COLUMNS: string[] = [
  "name",
  "input",
  "output",
  "duration",
];

const SELECTED_COLUMNS_KEY = "traces-selected-columns";
const COLUMNS_WIDTH_KEY = "traces-columns-width";
const COLUMNS_ORDER_KEY = "traces-columns-order";
const COLUMNS_SCORES_ORDER_KEY = "traces-scores-columns-order";
const DYNAMIC_COLUMNS_KEY = "traces-dynamic-columns";

type TracesSpansTabProps = {
  type: TRACE_DATA_TYPE;
  projectId: string;
  projectName: string;
};

export const TracesSpansTab: React.FC<TracesSpansTabProps> = ({
  type,
  projectId,
  projectName,
}) => {
  const [search = "", setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });

  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size = 100, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });

  const [height = ROW_HEIGHT.small, setHeight] = useQueryParam(
    "height",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending, refetch } = useTracesOrSpansList(
    {
      projectId,
      type: type as TRACE_DATA_TYPE,
      filters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: true,
    },
    {
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const { data: statisticData, refetch: refetchStatistic } =
    useTracesOrSpansStatistic(
      {
        projectId,
        type: type as TRACE_DATA_TYPE,
        filters,
        search: search as string,
      },
      {
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useTracesOrSpansScoresColumns(
      {
        projectId,
        type: type as TRACE_DATA_TYPE,
      },
      {
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? `There are no ${
        type === TRACE_DATA_TYPE.traces ? "traces" : "LLM calls"
      } yet`
    : "No search results";

  const rows: Array<Span | Trace> = useMemo(() => data?.content ?? [], [data]);
  const columnsStatistic: ColumnsStatistic = useMemo(
    () => statisticData?.stats ?? [],
    [statisticData],
  );

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_TRACES_PAGE_COLUMNS,
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
        id: `feedback_scores.${c.name}`,
        label: c.name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [feedbackScoresData?.scores]);

  const dynamicColumnsIds = useMemo(
    () => dynamicScoresColumns.map((c) => c.id),
    [dynamicScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

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
            statisticKey: `feedback_scores.${label}`,
          }) as ColumnData<BaseTraceData>,
      ),
    ];
  }, [dynamicScoresColumns]);

  const selectedRows: Array<Trace | Span> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleRowClick = useCallback(
    (row?: Trace | Span) => {
      if (!row) return;
      if (type === TRACE_DATA_TYPE.traces) {
        setTraceId((state) => (row.id === state ? "" : row.id));
        setSpanId("");
      } else {
        setTraceId((row as Span).trace_id);
        setSpanId((state) => (row.id === state ? "" : row.id));
      }
    },
    [setTraceId, setSpanId, type],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Trace | Span>(),
      mapColumnDataFields<BaseTraceData, Span | Trace>({
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: LinkCell as never,
        customMeta: {
          callback: handleRowClick,
          asId: true,
        },
      }),
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(
        TRACES_PAGE_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
        },
      ),
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(
        scoresColumnsData,
        {
          columnsOrder: scoresColumnsOrder,
          selectedColumns,
        },
      ),
    ];
  }, [
    handleRowClick,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
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

  const activeRowId = type === TRACE_DATA_TYPE.traces ? traceId : spanId;
  const rowIndex = findIndex(rows, (row) => activeRowId === row.id);

  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  const handleRowChange = useCallback(
    (shift: number) => handleRowClick(rows[rowIndex + shift]),
    [handleRowClick, rowIndex, rows],
  );

  const handleClose = useCallback(() => {
    setTraceId("");
    setSpanId("");
  }, [setSpanId, setTraceId]);

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

  if (isPending || isFeedbackScoresPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return <NoTracesPage />;
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by ID"
            className="w-[320px]"
          ></SearchInput>
          <FiltersButton
            columns={TRACES_PAGE_FILTERS_COLUMNS}
            filters={filters}
            onChange={setFilters}
          />
        </div>
        <div className="flex items-center gap-2">
          <TracesActionsPanel
            projectId={projectId}
            projectName={projectName}
            rows={selectedRows}
            columnsToExport={columnsToExport}
            type={type as TRACE_DATA_TYPE}
          />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
          <TooltipWrapper
            content={`Refresh ${
              type === TRACE_DATA_TYPE.traces ? "traces" : "LLM calls"
            } list`}
          >
            <Button
              variant="outline"
              size="icon"
              className="shrink-0"
              onClick={() => {
                refetch();
                refetchStatistic();
              }}
            >
              <RotateCw className="size-4" />
            </Button>
          </TooltipWrapper>
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={TRACES_PAGE_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
          ></ColumnsButton>
        </div>
      </div>
      <DataTable
        columns={columns}
        columnsStatistic={columnsStatistic}
        data={rows}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_TRACES_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={data?.total ?? 0}
        ></DataTablePagination>
      </div>
      <TraceDetailsPanel
        projectId={projectId}
        traceId={traceId as string}
        spanId={spanId as string}
        setSpanId={setSpanId}
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        onClose={handleClose}
        onRowChange={handleRowChange}
      />
    </div>
  );
};

export default TracesSpansTab;
