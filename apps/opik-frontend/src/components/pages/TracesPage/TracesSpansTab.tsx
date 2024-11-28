import React, { useCallback, useEffect, useMemo, useState } from "react";
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
import difference from "lodash/difference";
import union from "lodash/union";
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
import TracesActionsPanel from "@/components/pages/TracesPage/TracesActionsPanel";
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
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import TraceDetailsPanel from "@/components/shared/TraceDetailsPanel/TraceDetailsPanel";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import { formatDate } from "@/lib/date";
import useTracesOrSpansStatistic from "@/hooks/useTracesOrSpansStatistic";

const getRowId = (d: Trace | Span) => d.id;

const REFETCH_INTERVAL = 30000;

export const TRACES_PAGE_COLUMNS: ColumnData<BaseTraceData>[] = [
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
    accessorFn: (row) => (row.usage ? `${row.usage.total_tokens}` : ""),
  },
  {
    id: "usage.prompt_tokens",
    label: "Total input tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.usage ? `${row.usage.prompt_tokens}` : ""),
  },
  {
    id: "usage.completion_tokens",
    label: "Total output tokens",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.usage ? `${row.usage.completion_tokens}` : ""),
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
  ...TRACES_PAGE_COLUMNS,
];

export const DEFAULT_TRACES_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

export const DEFAULT_TRACES_PAGE_COLUMNS: string[] = [
  "name",
  "input",
  "output",
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

  const dynamicColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? []).map<DynamicColumn>((c) => ({
      id: `feedback_scores.${c.name}`,
      label: c.name,
      columnType: COLUMN_TYPE.number,
    }));
  }, [feedbackScoresData?.scores]);

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_TRACES_PAGE_COLUMNS,
    },
  );

  const [, setPresentedDynamicColumns] = useLocalStorageState<string[]>(
    DYNAMIC_COLUMNS_KEY,
    {
      defaultValue: [],
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

  useEffect(() => {
    setPresentedDynamicColumns((cols) => {
      const dynamicColumnsIds = dynamicColumns.map((col) => col.id);
      const newDynamicColumns = difference(dynamicColumnsIds, cols);

      if (newDynamicColumns.length > 0) {
        setSelectedColumns((selected) => union(selected, newDynamicColumns));
      }

      return union(dynamicColumnsIds, cols);
    });
  }, [dynamicColumns, setPresentedDynamicColumns, setSelectedColumns]);

  const dynamicColumnsData = useMemo(() => {
    return [
      ...dynamicColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            header: FeedbackScoreHeader as never,
            cell: FeedbackScoreCell as never,
            accessorFn: (row) =>
              row.feedback_scores?.find((f) => f.name === label),
            statisticKey: `feedback_score.${label}`,
          }) as ColumnData<BaseTraceData>,
      ),
    ];
  }, [dynamicColumns]);

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
        size: columnsWidth[COLUMN_ID_ID],
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
          columnsWidth,
          selectedColumns,
        },
      ),
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(
        dynamicColumnsData,
        {
          columnsOrder: scoresColumnsOrder,
          columnsWidth,
          selectedColumns,
        },
      ),
    ];
  }, [
    columnsWidth,
    handleRowClick,
    columnsOrder,
    selectedColumns,
    dynamicColumnsData,
    scoresColumnsOrder,
  ]);

  const columnsToExport = useMemo(() => {
    return columns
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) => selectedColumns.includes(c));
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
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

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
            extraSection={{
              title: "Feedback Scores",
              columns: dynamicColumnsData,
              order: scoresColumnsOrder,
              onOrderChange: setScoresColumnsOrder,
            }}
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
