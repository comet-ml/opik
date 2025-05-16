import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
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
  COLUMN_COMMENTS_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_GUARDRAIL_STATISTIC_ID,
  COLUMN_GUARDRAILS_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ColumnsStatistic,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { BaseTraceData, Span, Trace } from "@/types/traces";
import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
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
import PrettyCell from "@/components/shared/DataTableCells/PrettyCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ThreadDetailsPanel from "@/components/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import TraceDetailsPanel, {
  LastSection,
  LastSectionParam,
  LastSectionValue,
} from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import { formatDate, formatDuration } from "@/lib/date";
import useTracesOrSpansStatistic from "@/hooks/useTracesOrSpansStatistic";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import GuardrailsCell from "@/components/shared/DataTableCells/GuardrailsCell";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";

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
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "input",
    },
  },
  {
    id: "output",
    label: "Output",
    size: 400,
    type: COLUMN_TYPE.string,
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "output",
    },
  },
  {
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
    cell: DurationCell as never,
    statisticDataFormater: formatDuration,
  },
  {
    id: COLUMN_METADATA_ID,
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

const DEFAULT_TRACES_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

const DEFAULT_TRACES_PAGE_COLUMNS: string[] = [
  "name",
  "input",
  "output",
  "duration",
  COLUMN_COMMENTS_ID,
];

const SELECTED_COLUMNS_KEY = "traces-selected-columns";
const COLUMNS_WIDTH_KEY = "traces-columns-width";
const COLUMNS_ORDER_KEY = "traces-columns-order";
const COLUMNS_SORT_KEY_SUFFIX = "-columns-sort";
const COLUMNS_SCORES_ORDER_KEY = "traces-scores-columns-order";
const DYNAMIC_COLUMNS_KEY = "traces-dynamic-columns";
const PAGINATION_SIZE_KEY = "traces-pagination-size";
const ROW_HEIGHT_KEY = "traces-row-height";

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

  const [threadId = "", setThreadId] = useQueryParam("thread", StringParam, {
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

  const [, setLastSection] = useQueryParam("lastSection", LastSectionParam, {
    updateType: "replaceIn",
  });

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: ROW_HEIGHT_KEY,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [filters = [], setFilters] = useQueryParam(
    `${type}_filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );
  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: `${type}${COLUMNS_SORT_KEY_SUFFIX}`,
    queryKey: `${type}_sorting`,
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_METADATA_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete,
          keyComponentProps: {
            rootKeys: ["metadata"],
            projectId,
            type,
            placeholder: "key",
            excludeRoot: true,
          },
        },
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: TracesOrSpansFeedbackScoresSelect,
          keyComponentProps: {
            projectId,
            type,
            placeholder: "Select score",
          },
        },
      },
    }),
    [projectId, type],
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending, refetch } = useTracesOrSpansList(
    {
      projectId,
      type: type as TRACE_DATA_TYPE,
      sorting: sortedColumns,
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

  const rows: Array<Span | Trace> = useMemo(
    () => data?.content ?? [],
    [data?.content],
  );

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

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
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${c.name}`,
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
            statisticKey: `${COLUMN_FEEDBACK_SCORES_ID}.${label}`,
          }) as ColumnData<BaseTraceData>,
      ),
    ];
  }, [dynamicScoresColumns]);

  const selectedRows: Array<Trace | Span> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleRowClick = useCallback(
    (row?: Trace | Span, lastSection?: LastSectionValue) => {
      if (!row) return;
      if (type === TRACE_DATA_TYPE.traces) {
        setTraceId((state) => (row.id === state ? "" : row.id));
        setSpanId("");
      } else {
        setTraceId((row as Span).trace_id);
        setSpanId((state) => (row.id === state ? "" : row.id));
      }

      if (lastSection) {
        setLastSection(lastSection);
      }
    },
    [setTraceId, setSpanId, type, setLastSection],
  );

  const meta = useMemo(
    () => ({
      onCommentsReply: (row?: Trace | Span) => {
        handleRowClick(row, LastSection.Comments);
      },
    }),
    [handleRowClick],
  );

  const handleThreadIdClick = useCallback(
    (row?: Trace) => {
      if (!row) return;
      setThreadId(row.thread_id);
      setTraceId(row.id);
    },
    [setThreadId, setTraceId],
  );

  const columnData = useMemo(() => {
    return [
      ...SHARED_COLUMNS,
      ...(type === TRACE_DATA_TYPE.traces
        ? [
            {
              id: "span_count",
              label: "Span count",
              type: COLUMN_TYPE.number,
              accessorFn: (row: BaseTraceData) => get(row, "span_count", "-"),
            },
            {
              id: "thread_id",
              label: "Thread ID",
              type: COLUMN_TYPE.string,
              cell: LinkCell as never,
              customMeta: {
                callback: handleThreadIdClick,
                asId: true,
              },
            },
          ]
        : []),
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
      {
        id: COLUMN_COMMENTS_ID,
        label: "Comments",
        type: COLUMN_TYPE.string,
        cell: CommentsCell as never,
      },
      ...(isGuardrailsEnabled
        ? [
            {
              id: COLUMN_GUARDRAILS_ID,
              label: "Guardrails",
              statisticKey: COLUMN_GUARDRAIL_STATISTIC_ID,
              type: COLUMN_TYPE.guardrails,
              accessorFn: (row: BaseTraceData) =>
                row.guardrails_validations || [],
              cell: GuardrailsCell as never,
              statisticDataFormater: (value: number) => `${value} failed`,
            },
          ]
        : []),
    ];
  }, [type, handleThreadIdClick, isGuardrailsEnabled]);

  const filtersColumnData = useMemo(() => {
    return [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
      },
      ...SHARED_COLUMNS,
      ...(type === TRACE_DATA_TYPE.traces
        ? [
            {
              id: "thread_id",
              label: "Thread ID",
              type: COLUMN_TYPE.string,
            },
          ]
        : []),
      {
        id: COLUMN_FEEDBACK_SCORES_ID,
        label: "Feedback scores",
        type: COLUMN_TYPE.numberDictionary,
      },
      ...(isGuardrailsEnabled
        ? [
            {
              id: COLUMN_GUARDRAILS_ID,
              label: "Guardrails",
              type: COLUMN_TYPE.guardrails,
            },
          ]
        : []),
    ];
  }, [type, isGuardrailsEnabled]);

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
        sortable: isColumnSortable(COLUMN_ID_ID, sortableBy),
      }),
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(columnData, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(
        scoresColumnsData,
        {
          columnsOrder: scoresColumnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
    ];
  }, [
    handleRowClick,
    sortableBy,
    columnData,
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
            (DEFAULT_TRACES_COLUMN_PINNING.left || []).includes(c),
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

  if (isPending || isFeedbackScoresPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return <NoTracesPage />;
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
            columns={filtersColumnData}
            config={filtersConfig as never}
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
          <Separator orientation="vertical" className="mx-1 h-4" />
          <TooltipWrapper
            content={`Refresh ${
              type === TRACE_DATA_TYPE.traces ? "traces" : "LLM calls"
            } list`}
          >
            <Button
              variant="outline"
              size="icon-sm"
              className="shrink-0"
              onClick={() => {
                refetch();
                refetchStatistic();
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
            columns={columnData}
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
        columnPinning={DEFAULT_TRACES_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        stickyHeader
        meta={meta}
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
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        open={Boolean(traceId) && !threadId}
        onClose={handleClose}
        onRowChange={handleRowChange}
      />
      <ThreadDetailsPanel
        projectId={projectId}
        traceId={traceId!}
        setTraceId={setTraceId}
        threadId={threadId!}
        open={Boolean(threadId)}
        onClose={handleClose}
      />
    </>
  );
};

export default TracesSpansTab;
