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
import { ExternalLink } from "lucide-react";
import findIndex from "lodash/findIndex";
import isNumber from "lodash/isNumber";
import get from "lodash/get";
import keyBy from "lodash/keyBy";
import compact from "lodash/compact";
import {
  useMetricDateRangeWithQueryAndStorage,
  DATE_RANGE_PRESET_ALLTIME,
} from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import MetricDateRangeSelect from "@/v2/pages-shared/traces/MetricDateRangeSelect/MetricDateRangeSelect";

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
import { Thread, LOGS_SOURCE } from "@/types/traces";
import {
  convertColumnDataToColumn,
  injectColumnCallback,
  migrateSelectedColumns,
} from "@/lib/table";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { generateSelectColumDef } from "@/shared/DataTable/utils";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import { buildDocsUrl } from "@/v2/lib/utils";
import { useOpenQuickStartDialog } from "@/v2/pages-shared/onboarding/QuickstartDialog/QuickstartDialog";
import emptyLogsLightUrl from "/images/empty-logs-light.svg";
import emptyLogsDarkUrl from "/images/empty-logs-dark.svg";
import SearchInput from "@/shared/SearchInput/SearchInput";
import { Separator } from "@/ui/separator";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/shared/DataTableCells/IdCell";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import PrettyCell from "@/shared/DataTableCells/PrettyCell";
import CostCell from "@/shared/DataTableCells/CostCell";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import ThreadDetailsPanel from "@/v2/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import TraceDetailsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import ThreadsActionsPanel from "@/v2/pages/LogsPage/ThreadsTab/ThreadsActionsPanel";
import SelectionActionBar from "@/v2/components/SelectionActionBar/SelectionActionBar";
import useThreadList from "@/api/traces/useThreadsList";
import useThreadsStatistic from "@/api/traces/useThreadsStatistic";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import FeedbackScoreHeader from "@/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import DataTableStateHandler from "@/shared/DataTableStateHandler/DataTableStateHandler";
import FeedbackScoreCell from "@/shared/DataTableCells/FeedbackScoreCell";
import useThreadsFeedbackScoresNames from "@/api/traces/useThreadsFeedbackScoresNames";
import CommentsCell from "@/shared/DataTableCells/CommentsCell";
import { useTruncationEnabled } from "@/contexts/server-sync-provider";
import LogsTypeToggle from "@/v2/pages/LogsPage/LogsTypeToggle";
import { LOGS_TYPE } from "@/constants/traces";
import MetricsSummary from "@/v2/pages-shared/traces/MetricsSummary/MetricsSummary";
import useFilterChips from "@/shared/filter-chips/hooks/useFilterChips";
import FilterChipBar from "@/shared/filter-chips/FilterChipBar/FilterChipBar";
import { useTagsChipActions } from "@/shared/filter-chips/hooks/useTagsChipActions";
import {
  ChipDefinition,
  chipOptions,
  chipOptionsValue,
} from "@/shared/filter-chips/types";
import {
  TAGS_OPERATORS,
  FEEDBACK_SCORE_OPERATORS,
  STRING_OPERATORS,
  LIST_OPERATORS,
} from "@/shared/filter-chips/chips/QueryBuilderChip/operators";
import { useTagsOptions } from "@/v2/pages-shared/TagsAutocomplete/useTagsOptions";
import ListCell from "@/shared/DataTableCells/ListCell";
import { withExplain } from "@/v2/pages/LogsPage/explain/withExplain";
import {
  buildThreadCostTarget,
  buildThreadDurationTarget,
} from "@/v2/pages/LogsPage/ThreadsTab/explainTargets";

const getRowId = (d: Thread) => d.id;

const REFETCH_INTERVAL = 30000;

// Duration/Cost cells get the Ollie Explain button (OPIK-6425). Threads never
// change entity type, so the builders are bound once at module scope (unlike
// the per-view wrapping in TracesSpansTab). No-op in OSS (empty PluginsStore).
const DurationExplainCell = withExplain(
  DurationCell as never,
  buildThreadDurationTarget,
);
const CostExplainCell = withExplain(CostCell as never, buildThreadCostTarget);

const SHARED_COLUMNS: ColumnData<Thread>[] = [
  {
    id: "first_message",
    label: "First message",
    size: 400,
    type: COLUMN_TYPE.string,
    cell: PrettyCell as never,
    customMeta: {
      fieldType: "input",
      colorIndicator: true,
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
      colorIndicator: true,
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
    id: "duration",
    label: "Duration",
    type: COLUMN_TYPE.duration,
    cell: DurationExplainCell as never,
    statisticDataFormater: formatDuration,
    statisticTooltipFormater: formatDuration,
    supportsPercentiles: true,
  },
  {
    id: "tags",
    label: "Tags",
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
  },
  {
    id: "start_time",
    label: "Start time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    customMeta: {
      timeMode: "absolute",
    },
  },
  {
    id: "end_time",
    label: "End time",
    type: COLUMN_TYPE.time,
    cell: TimeCell as never,
    customMeta: {
      timeMode: "absolute",
    },
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
    cell: CostExplainCell as never,
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
  COLUMN_COMMENTS_ID,
];

const DEFAULT_THREADS_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  "start_time",
  "end_time",
  "first_message",
  "last_message",
  "number_of_messages",
  "duration",
  `${COLUMN_USAGE_ID}.total_tokens`,
  `${COLUMN_USAGE_ID}.prompt_tokens`,
  `${COLUMN_USAGE_ID}.completion_tokens`,
  "total_estimated_cost",
  "tags",
  COLUMN_COMMENTS_ID,
  "created_by",
];

const THREAD_CHIP_DEFINITIONS: ChipDefinition[] = [
  {
    id: "start_time",
    field: "start_time",
    label: "Start time",
    kind: "time",
    columnType: COLUMN_TYPE.time,
  },
  {
    id: "end_time",
    field: "end_time",
    label: "End time",
    kind: "time",
    columnType: COLUMN_TYPE.time,
  },
  {
    id: "duration",
    field: "duration",
    label: "Duration",
    kind: "numeric",
    columnType: COLUMN_TYPE.duration,
    format: "duration",
  },
  {
    id: "number_of_messages",
    field: "number_of_messages",
    label: "Message count",
    kind: "numeric",
    columnType: COLUMN_TYPE.number,
    format: "integer",
  },
  {
    id: "first_message",
    field: "first_message",
    label: "First message",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search first message" },
  },
  {
    id: "last_message",
    field: "last_message",
    label: "Last message",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Search last message" },
  },
  {
    id: "id",
    field: "id",
    label: "Thread ID",
    kind: "query-builder",
    columnType: COLUMN_TYPE.string,
    operators: STRING_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Enter thread ID" },
  },
  {
    id: "annotation_queue_ids",
    field: "annotation_queue_ids",
    label: "Annotation queue ID",
    kind: "query-builder",
    columnType: COLUMN_TYPE.list,
    operators: LIST_OPERATORS,
    defaultOperator: "contains",
    value: { placeholder: "Enter annotation queue ID" },
  },
];

const THREAD_CHIP_ORDER: string[] = [
  "start_time",
  "end_time",
  "duration",
  "number_of_messages",
  "first_message",
  "last_message",
  "tags",
  "id",
  "annotation_queue_ids",
  "feedback_scores",
];

const THREAD_DEFAULT_PINNED_CHIPS = ["tags", "duration", "start_time"];

const SELECTED_COLUMNS_KEY = "threads-selected-columns";
const SELECTED_COLUMNS_KEY_V2 = `${SELECTED_COLUMNS_KEY}-v2`;
const COLUMNS_WIDTH_KEY = "threads-columns-width";
const COLUMNS_ORDER_KEY = "threads-columns-order";
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
  const { open: openQuickstart } = useOpenQuickStartDialog();
  const truncationEnabled = useTruncationEnabled();

  const {
    dateRange,
    handleDateRangeChange,
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQueryAndStorage({
    excludePresets: [DATE_RANGE_PRESET_ALLTIME],
  });
  const [search = "", setSearch] = useQueryParam(
    "threads_search",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );
  const trimmedSearch = (search as string).trim().toLowerCase();

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

  const handleChipFiltersChange = useCallback(() => {
    setPage(1);
  }, [setPage]);

  const threadScoreOptions = useMemo(
    () => ({
      items: (feedbackScoresNames?.scores ?? []).map((s) => s.name),
      isLoading: isFeedbackScoresNamesPending,
    }),
    [feedbackScoresNames, isFeedbackScoresNamesPending],
  );

  const threadChipDefinitions = useMemo<ChipDefinition[]>(() => {
    const dynamicChips: Record<string, ChipDefinition> = {
      tags: {
        id: "tags",
        field: "tags",
        label: "Tags",
        kind: "query-builder",
        columnType: COLUMN_TYPE.list,
        operators: TAGS_OPERATORS,
        defaultOperator: "contains",
        value: {
          placeholder: "Type a tag…",
          options: chipOptions(useTagsOptions, {
            projectId,
            entityType: "threads",
          }),
        },
        addLabel: "Add tag",
      },
      feedback_scores: {
        id: "feedback_scores",
        field: COLUMN_FEEDBACK_SCORES_ID,
        label: "Feedback scores",
        kind: "query-builder",
        columnType: COLUMN_TYPE.numberDictionary,
        operators: FEEDBACK_SCORE_OPERATORS,
        defaultOperator: ">=",
        key: {
          placeholder: "Select score",
          options: chipOptionsValue(threadScoreOptions),
        },
        value: { type: "numeric", decimals: 2, placeholder: "0" },
      },
    };
    const byId: Record<string, ChipDefinition> = {
      ...keyBy(THREAD_CHIP_DEFINITIONS, "id"),
      ...dynamicChips,
    };
    return compact(THREAD_CHIP_ORDER.map((id) => byId[id]));
  }, [projectId, threadScoreOptions]);

  const {
    chipsPinned: threadChipsPinned,
    chipsUnpinned: threadChipsUnpinned,
    values: threadChipValues,
    filters: threadChipFilters,
    applyValue: applyThreadChipValue,
    clearValue: clearThreadChipValue,
    clearAll: clearAllThreadChips,
    pinChip: pinThreadChip,
    unpinChip: unpinThreadChip,
    managerOpen: threadChipManagerOpen,
    setManagerOpen: setThreadChipManagerOpen,
    openChipId: threadOpenChipId,
    setOpenChipId: setThreadOpenChipId,
  } = useFilterChips({
    tableId: "logs.threads",
    urlKey: "threads_filters",
    definitions: threadChipDefinitions,
    defaultPinned: THREAD_DEFAULT_PINNED_CHIPS,
    onChange: handleChipFiltersChange,
  });

  const { addTag: addThreadTagFilter } = useTagsChipActions({
    chipId: "tags",
    values: threadChipValues,
    applyValue: applyThreadChipValue,
    pinChip: pinThreadChip,
  });

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
        filters: threadChipFilters,
        page: page as number,
        size: size as number,
        search: trimmedSearch,
        truncate: truncationEnabled,
        fromTime: intervalStart,
        toTime: intervalEnd,
        logsSource: LOGS_SOURCE.sdk,
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
      filters: threadChipFilters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: false,
      fromTime: intervalStart,
      toTime: intervalEnd,
      logsSource: LOGS_SOURCE.sdk,
    },
    {
      enabled: false,
      refetchOnMount: "always",
    },
  );

  const { data: statisticData } = useThreadsStatistic(
    {
      projectId,
      filters: threadChipFilters,
      search: search as string,
      fromTime: intervalStart,
      toTime: intervalEnd,
      logsSource: LOGS_SOURCE.sdk,
    },
    {
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const { data: existenceData } = useThreadList(
    {
      projectId,
      page: 1,
      size: 1,
      fromTime: intervalStart,
      toTime: intervalEnd,
      logsSource: LOGS_SOURCE.sdk,
    },
    {
      enabled: isTableDataEnabled,
    },
  );
  const hasProjectData = (existenceData?.total ?? 0) > 0;

  const handleClearFilters = useCallback(() => {
    setSearch("");
    clearAllThreadChips();
    setPage(1);
  }, [setSearch, clearAllThreadChips, setPage]);

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
    return dynamicScoresColumns.map(
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
    COLUMNS_ORDER_KEY,
    {
      defaultValue: DEFAULT_THREADS_COLUMNS_ORDER,
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
    const columnDefsWithTagClick: ColumnData<Thread>[] = DEFAULT_COLUMNS.map(
      (col) =>
        col.id === "tags"
          ? {
              ...col,
              customMeta: {
                ...col.customMeta,
                onItemClick: addThreadTagFilter,
                getItemTooltip: (tag: string) => `Filter by tag: "${tag}"`,
              },
            }
          : col,
    );
    const convertedColumns = convertColumnDataToColumn<Thread, Thread>(
      columnDefsWithTagClick,
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
    addThreadTagFilter,
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
    !isTableLoading && !hasProjectData && rows.length === 0 && page === 1;

  return (
    <>
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2"
        direction="horizontal"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <LogsTypeToggle value={logsType} onValueChange={onLogsTypeChange} />
        </div>
        <div className="flex items-center gap-2">
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
            size="icon-xs"
          />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
            sections={columnSections}
            layout="labeled"
            size="xs"
          />
          <Separator orientation="vertical" className="mx-2 h-6" />
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
            hideAlltime
          />
          <Separator orientation="vertical" className="mx-2 h-6" />
          <RefreshButton
            tooltip="Refresh threads list"
            size="icon-xs"
            isFetching={isFetching}
            onRefresh={() => refetch()}
          />
        </div>
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="pt-3"
        direction="horizontal"
        limitWidth
      >
        <MetricsSummary
          projectId={projectId}
          entityType="threads"
          countLabel="Threads"
          filters={threadChipFilters}
          intervalStart={intervalStart}
          intervalEnd={intervalEnd}
          dateRange={dateRange}
          logsSource={LOGS_SOURCE.sdk}
        />
      </PageBodyStickyContainer>

      {selectedRows.length > 0 ? (
        <SelectionActionBar
          selectedCount={selectedRows.length}
          onDeselectAll={() => setRowSelection({})}
        >
          <ThreadsActionsPanel
            projectId={projectId}
            projectName={projectName}
            getDataForExport={getDataForExport}
            selectedRows={selectedRows}
            columnsToExport={columnsToExport}
            buttonVariant="ghostInverted"
          />
        </SelectionActionBar>
      ) : (
        <PageBodyStickyContainer
          className="py-3"
          direction="bidirectional"
          limitWidth
        >
          <FilterChipBar
            chipsPinned={threadChipsPinned}
            chipsUnpinned={threadChipsUnpinned}
            values={threadChipValues}
            managerOpen={threadChipManagerOpen}
            onManagerOpenChange={setThreadChipManagerOpen}
            onApplyValue={applyThreadChipValue}
            onClearValue={clearThreadChipValue}
            onPinChip={pinThreadChip}
            onUnpinChip={unpinThreadChip}
            onClearAll={clearAllThreadChips}
            openChipId={threadOpenChipId}
            onOpenChipIdChange={setThreadOpenChipId}
            prefix={
              <SearchInput
                searchText={search as string}
                setSearchText={setSearch}
                placeholder="Search by anything"
                className="w-[200px] shrink-0"
                dimension="xs"
              />
            }
          />
        </PageBodyStickyContainer>
      )}

      <DataTableStateHandler
        isLoading={isTableLoading}
        isEmpty={showEmptyState}
        emptyState={
          <DataTableEmptyContent
            title="No threads yet"
            description="Threads will appear here once your agent starts receiving requests."
            lightImageUrl={emptyLogsLightUrl}
            darkImageUrl={emptyLogsDarkUrl}
          >
            <div className="flex items-center gap-3">
              <button
                onClick={openQuickstart}
                className="comet-body-s underline underline-offset-4 hover:text-primary"
              >
                Quickstart guide
              </button>
              <a
                href={buildDocsUrl("/tracing/advanced/log_chat_conversations")}
                target="_blank"
                rel="noreferrer"
                className="comet-body-s inline-flex items-center gap-1 underline underline-offset-4 hover:text-primary"
              >
                View docs
                <ExternalLink className="size-3" />
              </a>
            </div>
          </DataTableEmptyContent>
        }
        skeleton
      >
        <DataTable
          columns={columns}
          columnsStatistic={columnsStatistic}
          data={rows}
          onRowClick={handleRowClick}
          activeRowId={activeRowId ?? ""}
          sortConfig={sortConfig}
          resizeConfig={resizeConfig}
          showSkeleton={isTableLoading}
          selectionConfig={{
            rowSelection,
            setRowSelection,
          }}
          getRowId={getRowId}
          rowHeight={height as ROW_HEIGHT}
          columnPinning={DEFAULT_COLUMN_PINNING}
          noData={
            <DataTableNoMatchingData
              onClearFilters={
                search || threadChipFilters.length > 0
                  ? handleClearFilters
                  : undefined
              }
            />
          }
          TableWrapper={PageBodyStickyTableWrapper}
          stickyHeader
          meta={meta}
          showLoadingOverlay={isPlaceholderData && isFetching}
        />
        <PageBodyStickyContainer
          className="bottom-0 -mt-px border-t border-border py-2 pb-4"
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
