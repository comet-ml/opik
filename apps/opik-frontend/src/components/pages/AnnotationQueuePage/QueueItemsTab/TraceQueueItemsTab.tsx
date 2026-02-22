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
  CellContext,
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import isObject from "lodash/isObject";
import isNumber from "lodash/isNumber";
import get from "lodash/get";

import {
  COLUMN_COMMENTS_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_METADATA_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ColumnsStatistic,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { Trace } from "@/types/traces";
import { AnnotationQueue } from "@/types/annotation-queues";
import {
  convertColumnDataToColumn,
  injectColumnCallback,
  migrateSelectedColumns,
} from "@/lib/table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import {
  generateActionsColumDef,
  generateSelectColumDef,
  getRowId,
} from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import { Separator } from "@/components/ui/separator";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import NoDataPage from "@/components/shared/NoDataPage/NoDataPage";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import ErrorCell from "@/components/shared/DataTableCells/ErrorCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import PrettyCell from "@/components/shared/DataTableCells/PrettyCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import QueueItemActionsPanel from "@/components/pages/AnnotationQueuePage/QueueItemsTab/QueueItemActionsPanel";
import QueueItemRowActionsCell from "@/components/pages/AnnotationQueuePage/QueueItemsTab/QueueItemRowActionsCell";
import NoQueueItemsPage from "@/components/pages/AnnotationQueuePage/QueueItemsTab/NoQueueItemsPage";
import useTracesList from "@/api/traces/useTracesList";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import TimeCell from "@/components/shared/DataTableCells/TimeCell";
import { generateTracesURL } from "@/lib/annotation-queues";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import useAppStore from "@/store/AppStore";
import { generateAnnotationQueueIdFilter } from "@/lib/filters";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import SelectBox, {
  SelectBoxProps,
} from "@/components/shared/SelectBox/SelectBox";
import { useTruncationEnabled } from "@/components/server-sync-provider";

const TRACE_COLUMNS: ColumnData<Trace>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
    sortable: true,
  },
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
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
    statisticTooltipFormater: formatDuration,
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
    iconType: "tags",
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
    size: 160,
    statisticDataFormater: formatCost,
    statisticTooltipFormater: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
  {
    id: "llm_span_count",
    label: "LLM calls count",
    type: COLUMN_TYPE.number,
    accessorFn: (row: Trace) => get(row, "llm_span_count", "-"),
  },
  {
    id: "thread_id",
    label: "Thread ID",
    type: COLUMN_TYPE.string,
    cell: LinkCell as never,
    customMeta: {
      asId: true,
    },
  },
  {
    id: "error_info",
    label: "Errors",
    statisticKey: "error_count",
    type: COLUMN_TYPE.errors,
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
];

const TRACE_FILTER_COLUMNS: ColumnData<Trace>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  ...TRACE_COLUMNS,
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "name",
  "input",
  "output",
  "comments",
];

const SELECTED_COLUMNS_KEY = "queue-trace-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "queue-trace-columns-width";
const COLUMNS_ORDER_KEY = "queue-trace-columns-order";
const COLUMNS_SORT_KEY = "queue-trace-columns-sort";
const COLUMNS_SCORES_ORDER_KEY = "queue-trace-scores-columns-order";
const DYNAMIC_COLUMNS_KEY = "queue-trace-dynamic-columns";
const PAGINATION_SIZE_KEY = "queue-trace-pagination-size";
const ROW_HEIGHT_KEY = "queue-trace-row-height";

type TraceQueueItemsTabProps = {
  annotationQueue: AnnotationQueue;
};

const TraceQueueItemsTab: React.FC<TraceQueueItemsTabProps> = ({
  annotationQueue,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const truncationEnabled = useTruncationEnabled();

  const [search = "", setSearch] = useQueryParam("trace_search", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("trace_page", NumberParam, {
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
    queryKey: "trace_height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [filters = [], setFilters] = useQueryParam("trace_filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: COLUMNS_SORT_KEY,
    queryKey: "trace_sorting",
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY_V2,
    {
      defaultValue: migrateSelectedColumns(
        SELECTED_COLUMNS_KEY,
        DEFAULT_SELECTED_COLUMNS,
        [COLUMN_ID_ID],
      ),
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

  const extendedFilters = useMemo(
    () => [...filters, ...generateAnnotationQueueIdFilter(annotationQueue.id)],
    [annotationQueue.id, filters],
  );

  const { data, isPending, isPlaceholderData, isFetching } = useTracesList(
    {
      projectId: annotationQueue.project_id,
      sorting: sortedColumns,
      filters: extendedFilters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: truncationEnabled,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const { data: statisticData } = useTracesStatistic(
    {
      projectId: annotationQueue.project_id,
      filters,
      search: search as string,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: (
            props: {
              onValueChange: SelectBoxProps<string>["onChange"];
            } & SelectBoxProps<string>,
          ) => <SelectBox {...props} onChange={props.onValueChange} />,
          keyComponentProps: {
            options: (annotationQueue.feedback_definition_names ?? [])
              .sort()
              .map((key) => ({ value: key, label: key })),
            placeholder: "Select score",
          },
        },
      },
    }),
    [annotationQueue.feedback_definition_names],
  );

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? "There are no items in this queue yet"
    : "No search results";

  const rows: Trace[] = useMemo(() => data?.content ?? [], [data]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const columnsStatistic: ColumnsStatistic = useMemo(
    () => statisticData?.stats ?? [],
    [statisticData],
  );

  const dynamicScoresColumns = useMemo(() => {
    return (annotationQueue.feedback_definition_names ?? [])
      .sort()
      .map<DynamicColumn>((name) => ({
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${name}`,
        label: name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [annotationQueue.feedback_definition_names]);

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
            statisticDataFormater: formatScoreDisplay,
          }) as ColumnData<Trace>,
      ),
    ];
  }, [dynamicScoresColumns]);

  const selectedRows: Trace[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  // TODO: Temporary workaround to open in new tab until sidebars are integrated in the page
  const handleRowClick = useCallback(
    (row: Trace) => {
      if (!row) return;

      const url = generateTracesURL(
        workspaceName,
        annotationQueue.project_id,
        "traces",
        row.id,
      );
      window.open(url, "_blank");
    },
    [workspaceName, annotationQueue.project_id],
  );

  const handleThreadIdClick = useCallback(
    (row: Trace) => {
      if (!row || !row.thread_id) return;

      const url = generateTracesURL(
        workspaceName,
        annotationQueue.project_id,
        "threads",
        row.thread_id,
      );
      window.open(url, "_blank");
    },
    [workspaceName, annotationQueue.project_id],
  );

  const columns = useMemo(() => {
    const convertedColumns = convertColumnDataToColumn<Trace, Trace>(
      TRACE_COLUMNS,
      {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      },
    );

    return [
      generateSelectColumDef<Trace>(),
      ...injectColumnCallback(
        convertedColumns,
        "thread_id",
        handleThreadIdClick,
      ),
      ...convertColumnDataToColumn<Trace, Trace>(scoresColumnsData, {
        columnsOrder: scoresColumnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      generateActionsColumDef({
        cell: QueueItemRowActionsCell as React.FC<CellContext<Trace, unknown>>,
        customMeta: {
          annotationQueueId: annotationQueue.id,
        },
      }),
    ];
  }, [
    sortableBy,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
    annotationQueue.id,
    handleThreadIdClick,
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

  if (isPending) {
    return <Loader />;
  }

  if (noData && rows.length === 0 && page === 1) {
    return (
      <NoQueueItemsPage
        queueScope={annotationQueue.scope}
        annotationQueue={annotationQueue}
        Wrapper={NoDataPage}
        height={278}
        className="px-6"
      />
    );
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
          />
          <FiltersButton
            columns={TRACE_FILTER_COLUMNS}
            config={filtersConfig as never}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          <QueueItemActionsPanel
            items={selectedRows}
            annotationQueueId={annotationQueue.id}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={TRACE_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
          />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        columnsStatistic={columnsStatistic}
        data={rows}
        onRowClick={handleRowClick}
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
    </>
  );
};

export default TraceQueueItemsTab;
