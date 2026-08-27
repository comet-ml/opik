import React, { useCallback, useMemo, useState } from "react";
import { keepPreviousData } from "@tanstack/react-query";
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
import isObject from "lodash/isObject";
import isNumber from "lodash/isNumber";
import isArray from "lodash/isArray";
import get from "lodash/get";
import uniq from "lodash/uniq";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
  DATE_RANGE_PRESET_ALLTIME,
} from "@/v2/pages-shared/traces/MetricDateRangeSelect";
import { DateRangePreset } from "@/shared/DateRangeSelect";

import useTracesList from "@/api/traces/useTracesList";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_EXPERIMENT_ID,
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
import {
  normalizeMetadataPaths,
  buildDynamicMetadataColumns,
} from "@/lib/metadata";
import {
  BaseTraceData,
  Trace,
  LOGS_SOURCE,
  TRACE_VISIBILITY_MODE,
} from "@/types/traces";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import { getJSONPaths, cn } from "@/lib/utils";
import {
  generateSelectColumDef,
  getVirtualizationConfig,
} from "@/shared/DataTable/utils";
import SearchInput from "@/shared/SearchInput/SearchInput";
import useFilterChips from "@/shared/filter-chips/hooks/useFilterChips";
import FilterChipBar from "@/shared/filter-chips/FilterChipBar/FilterChipBar";
import { ChipOptionsResult } from "@/shared/filter-chips/types";
import {
  TRACE_DEFAULT_PINNED_CHIPS,
  buildTraceChipDefinitions,
} from "@/v2/pages-shared/traces/traceChipDefinitions";
import SelectionActionBar from "@/v2/components/SelectionActionBar/SelectionActionBar";
import useSpansFeedbackScoresNames from "@/api/traces/useSpansFeedbackScoresNames";
import { useIsFeatureEnabled } from "@/contexts/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import TracesActionsPanel from "@/v2/pages-shared/traces/TracesActionsPanel/TracesActionsPanel";
import MetricsSummary from "@/v2/pages-shared/traces/MetricsSummary/MetricsSummary";
import { Separator } from "@/ui/separator";
import { Tag } from "@/ui/tag";
import { Lock } from "lucide-react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/shared/DataTable/DataTableVirtualBody";
import { DataTableWrapperProps } from "@/shared/DataTable/DataTableWrapper";
import TableScrollContainer from "@/shared/DataTable/TableScrollContainer";
import ScrollTableWrapper from "@/shared/DataTable/ScrollTableWrapper";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import emptyLogsLightUrl from "/images/empty-logs-light.svg";
import emptyLogsDarkUrl from "/images/empty-logs-dark.svg";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
import PageBodyStickyContainer from "@/shared/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/v2/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import LinkCell from "@/shared/DataTableCells/LinkCell";
import ResourceCell from "@/shared/DataTableCells/ResourceCell";
import { RESOURCE_TYPE } from "@/shared/ResourceLink/ResourceLink";
import IdCell from "@/shared/DataTableCells/IdCell";
import CodeCell from "@/shared/DataTableCells/CodeCell";
import AutodetectCell from "@/shared/DataTableCells/AutodetectCell";
import ListCell from "@/shared/DataTableCells/ListCell";
import CostCell from "@/shared/DataTableCells/CostCell";
import ErrorCell from "@/shared/DataTableCells/ErrorCell";
import DurationCell from "@/shared/DataTableCells/DurationCell";
import FeedbackScoreCell from "@/shared/DataTableCells/FeedbackScoreCell";
import PrettyCell from "@/shared/DataTableCells/PrettyCell";
import CommentsCell from "@/shared/DataTableCells/CommentsCell";
import FeedbackScoreHeader from "@/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import DataTableStateHandler from "@/shared/DataTableStateHandler/DataTableStateHandler";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import useTraceThreadPanelsState from "@/v2/pages-shared/traces/useTraceThreadPanelsState";
import { Filter } from "@/types/filters";
import { useTruncationEnabled } from "@/contexts/server-sync-provider";
import {
  TLS_QUERY_PREFIX,
  TLS_STORAGE_PREFIX,
} from "@/v2/pages-shared/traces/TraceLogsView/constants";

export { TLS_QUERY_PREFIX };

const getRowId = (d: Trace) => d.id;

export const DEFAULT_SCOPE_TOOLTIP =
  "These traces are locked to this scope and can't be changed via filters";

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
    id: "error_info",
    label: "Errors",
    statisticKey: "error_count",
    type: COLUMN_TYPE.errors,
    cell: ErrorCell as never,
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
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.hows_the_cost_estimated],
    size: 160,
    statisticDataFormater: formatCost,
    statisticTooltipFormater: (value: number) =>
      formatCost(value, { modifier: "full" }),
  },
];

const METADATA_MAIN_COLUMN_DATA: ColumnData<BaseTraceData>[] = [
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
];

const DEFAULT_TRACES_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_TRACES_COLUMNS: string[] = [
  "start_time",
  "input",
  "output",
  "error_info",
  "duration",
  "usage.total_tokens",
  "total_estimated_cost",
  "tags",
  COLUMN_COMMENTS_ID,
];

// Per-view behavior of the logs view. Defaults serve the entity-scoped logs views (experiments,
// playground, trials, annotation queues): these are already narrowed to one entity, so they show
// traces of every visibility (experiment runs auto-create hidden traces; scoping, not visibility,
// is what isolates them). Callers that need a variant (e.g. the evaluation-traces view, which pins
// hidden) pass an override so this shared component stays free of scenario-specific branching.
export type TraceLogsViewConfig = {
  // Suffix appended to the localStorage key prefix to isolate this view's column state.
  storageNamespace: string;
  defaultColumns: string[];
  autoSelectScoreColumns: boolean;
  showMetricsSummary: boolean;
  visibilityMode: TRACE_VISIBILITY_MODE;
  // These views are already narrowed to one entity, so a trailing window only hides traces of
  // anything older than it — an experiment run two months ago would open on an empty table. A range
  // the user picks explicitly still wins over this.
  defaultDateRangePreset: DateRangePreset;
  // Row-height, date-range and refresh controls. The experiment Logs tab shows only the columns
  // selector per its design; the overlay hosts keep the full set they have today. Turning the date
  // range off also stops the view constraining by date at all — an invisible window would be the
  // very trap the all-time default exists to avoid.
  showTableControls: boolean;
};

export const DEFAULT_TRACE_LOGS_VIEW_CONFIG: TraceLogsViewConfig = {
  storageNamespace: "",
  defaultColumns: DEFAULT_TRACES_COLUMNS,
  autoSelectScoreColumns: true,
  showMetricsSummary: false,
  visibilityMode: TRACE_VISIBILITY_MODE.all,
  defaultDateRangePreset: DATE_RANGE_PRESET_ALLTIME,
  showTableControls: true,
};

// Stable empty reference for the "don't auto-select score columns" case (keeps hook deps steady).
const NO_DYNAMIC_COLUMNS: string[] = [];

// Stable empty reference so an unscoped host doesn't churn the effective-filters memo.
const NO_SCOPE_FILTERS: Filter[] = [];

const DEFAULT_TRACES_COLUMNS_ORDER: string[] = [
  COLUMN_ID_ID,
  "start_time",
  "end_time",
  "input",
  "output",
  "error_info",
  "duration",
  "usage.total_tokens",
  "usage.prompt_tokens",
  "usage.completion_tokens",
  "total_estimated_cost",
  "tags",
  COLUMN_COMMENTS_ID,
  "name",
  "span_count",
  "llm_span_count",
  "thread_id",
  COLUMN_EXPERIMENT_ID,
  "created_by",
];

const COLUMN_DATA: ColumnData<BaseTraceData>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
    sortable: true,
  },
  ...SHARED_COLUMNS,
  {
    id: "span_count",
    label: "Span count",
    type: COLUMN_TYPE.number,
    accessorFn: (row: BaseTraceData) => get(row, "span_count", "-"),
  },
  {
    id: "llm_span_count",
    label: "LLM calls count",
    type: COLUMN_TYPE.number,
    accessorFn: (row: BaseTraceData) => get(row, "llm_span_count", "-"),
  },
  {
    id: "thread_id",
    label: "Thread ID",
    type: COLUMN_TYPE.string,
    cell: LinkCell as never,
    customMeta: {
      asId: true,
    },
    explainer: EXPLAINERS_MAP[EXPLAINER_ID.what_are_threads],
  },
  {
    id: COLUMN_EXPERIMENT_ID,
    label: "Experiment",
    type: COLUMN_TYPE.string,
    cell: ResourceCell as never,
    customMeta: {
      nameKey: "experiment.name",
      idKey: "experiment.dataset_id",
      resource: RESOURCE_TYPE.experiment,
      getSearch: (row: BaseTraceData) => ({
        experiments: [get(row, "experiment.id")],
      }),
    },
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

const SELECTED_COLUMNS_KEY_V2_SUFFIX = "selected-columns-v2";
const COLUMNS_WIDTH_KEY_SUFFIX = "columns-width";
const COLUMNS_ORDER_KEY_SUFFIX = "columns-order";
const COLUMNS_SORT_KEY_SUFFIX = "columns-sort";
const COLUMNS_SCORES_ORDER_KEY_SUFFIX = "scores-columns-order";
const COLUMNS_METADATA_ORDER_KEY_SUFFIX = "metadata-columns-order";
const DYNAMIC_COLUMNS_KEY_SUFFIX = "dynamic-columns";
const PAGINATION_SIZE_KEY_SUFFIX = "pagination-size";
const ROW_HEIGHT_KEY_SUFFIX = "row-height";

export type TraceLogsViewProps = {
  projectId: string;
  projectName?: string;
  logsSource?: LOGS_SOURCE;
  viewConfig?: TraceLogsViewConfig;
  // Locked scope: always constrains the query and is never shown in the editable filter bar, so it
  // can't be changed or removed. Hosts that keep their scope in the URL (the sidebar, driven by
  // per-row triggers) read it there and pass it down; hosts that know it statically (the experiment
  // Logs tab) pass it directly. User filters layer on top with AND semantics.
  scopeFilters?: Filter[];
  // When set, a read-only indicator of what the view is locked to is rendered next to the filters.
  scopeLabel?: string;
  scopeTooltip?: string;
  // Element the trace/thread detail panels portal into. The sidebar passes its sheet content so the
  // panels stay within the overlay; page-level hosts leave it undefined to render at page level.
  container?: HTMLDivElement | null;
  // Gates the data queries. The sidebar passes its open flag so a closed overlay fetches nothing;
  // hosts that unmount when hidden (e.g. a tab) can leave this at its default.
  enabled?: boolean;
  // "sheet" fills the overlay and scrolls the table inside itself. "page" hands scrolling to the
  // host page's scroll container and pins the toolbar and pagination with its sticky containers,
  // matching the sibling tabs on the experiment page.
  layout?: "sheet" | "page";
  className?: string;
};

const TraceLogsView: React.FunctionComponent<TraceLogsViewProps> = ({
  projectId,
  projectName = "",
  logsSource,
  viewConfig = DEFAULT_TRACE_LOGS_VIEW_CONFIG,
  scopeFilters = NO_SCOPE_FILTERS,
  scopeLabel,
  scopeTooltip = DEFAULT_SCOPE_TOOLTIP,
  container,
  enabled = true,
  layout = "sheet",
  className,
}) => {
  const isPageLayout = layout === "page";
  const type = TRACE_DATA_TYPE.traces;
  const truncationEnabled = useTruncationEnabled();

  const storagePrefix = `${TLS_STORAGE_PREFIX}${viewConfig.storageNamespace}`;

  const {
    dateRange,
    handleDateRangeChange,
    intervalStart: rawIntervalStart,
    intervalEnd: rawIntervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQueryAndStorage({
    defaultValue: viewConfig.defaultDateRangePreset,
    // Namespaced storage so the all-time default actually applies: the key is otherwise shared with
    // the main Logs page, whose stored trailing window would override it for anyone who has ever
    // changed the range there. The URL key stays shared where the control is visible, so existing
    // deep links keep working — and is namespaced where it isn't, since the hook always writes it
    // and a shared param nothing reads back would just be litter.
    localStorageKey: `${storagePrefix}date-range`,
    // Namespaced URL key too. On the shared `time_range` the query value wins over storage, so a
    // stray time_range in the host page's URL silently defeated the all-time default; and the hook
    // writes its key on mount, so opening an overlay stamped time_range into the host URL and left
    // it there. Every other param this view owns is tls_-prefixed; this one now matches.
    key: `${TLS_QUERY_PREFIX}time_range`,
  });

  // With the date control hidden the view spans all time: a window nobody can see or change is
  // worse than no window at all.
  const intervalStart = viewConfig.showTableControls
    ? rawIntervalStart
    : undefined;
  const intervalEnd = viewConfig.showTableControls ? rawIntervalEnd : undefined;

  const [search = "", setSearch] = useQueryParam(
    `${TLS_QUERY_PREFIX}search`,
    StringParam,
    {
      updateType: "replaceIn",
    },
  );
  // The param default only covers undefined; useQueryParam can hand back null.
  const searchText = search ?? "";
  const trimmedSearch = searchText.trim().toLowerCase();

  const [page = 1, setPage] = useQueryParam(
    `${TLS_QUERY_PREFIX}page`,
    NumberParam,
    {
      updateType: "replaceIn",
    },
  );

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: `${storagePrefix}${PAGINATION_SIZE_KEY_SUFFIX}`,
    queryKey: `${TLS_QUERY_PREFIX}size`,
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `${storagePrefix}${ROW_HEIGHT_KEY_SUFFIX}`,
    queryKey: `${TLS_QUERY_PREFIX}height`,
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const isGuardrailsEnabled = useIsFeatureEnabled(
    FeatureToggleKeys.GUARDRAILS_ENABLED,
  );

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useTracesFeedbackScoresNames(
      {
        projectId,
      },
      {
        enabled,
      },
    );

  const { data: spanFeedbackScoresData, isPending: isSpanScoresPending } =
    useSpansFeedbackScoresNames(
      {
        projectId,
      },
      {
        enabled,
      },
    );

  const traceScoreOptions: ChipOptionsResult = useMemo(
    () => ({
      items: (feedbackScoresData?.scores ?? []).map((s) => s.name),
      isLoading: isFeedbackScoresPending,
    }),
    [feedbackScoresData?.scores, isFeedbackScoresPending],
  );

  const spanScoreOptions: ChipOptionsResult = useMemo(
    () => ({
      items: (spanFeedbackScoresData?.scores ?? []).map((s) => s.name),
      isLoading: isSpanScoresPending,
    }),
    [spanFeedbackScoresData?.scores, isSpanScoresPending],
  );

  const chipDefinitions = useMemo(
    () =>
      buildTraceChipDefinitions({
        projectId,
        traceScoreOptions,
        spanScoreOptions,
        isGuardrailsEnabled,
        logsSource,
      }),
    [
      projectId,
      traceScoreOptions,
      spanScoreOptions,
      isGuardrailsEnabled,
      logsSource,
    ],
  );

  const handleChipFiltersChange = useCallback(() => {
    setPage(1);
  }, [setPage]);

  const {
    chipsPinned,
    chipsUnpinned,
    values: chipValues,
    filters: chipFilters,
    applyValue: applyChipValue,
    clearValue: clearChipValue,
    clearAll: clearAllChips,
    pinChip,
    unpinChip,
    managerOpen: chipManagerOpen,
    setManagerOpen: setChipManagerOpen,
    openChipId,
    setOpenChipId,
  } = useFilterChips({
    // Pinned-chip config is per view so the experiment tab and the playground overlay don't fight
    // over one arrangement; the URL key stays tls_filters, whose raw Filter[] shape the chip bar
    // reads and writes unchanged, so existing deep links keep resolving.
    tableId: `logs.entity-traces.${viewConfig.storageNamespace || "default"}`,
    urlKey: `${TLS_QUERY_PREFIX}filters`,
    definitions: chipDefinitions,
    defaultPinned: TRACE_DEFAULT_PINNED_CHIPS,
    onChange: handleChipFiltersChange,
  });

  const effectiveFilters = useMemo(
    () => [...scopeFilters, ...chipFilters],
    [scopeFilters, chipFilters],
  );

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: `${storagePrefix}${COLUMNS_SORT_KEY_SUFFIX}`,
    queryKey: `${TLS_QUERY_PREFIX}sort`,
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${storagePrefix}${SELECTED_COLUMNS_KEY_V2_SUFFIX}`,
    {
      defaultValue: migrateSelectedColumns(
        `${storagePrefix}selected-columns`,
        viewConfig.defaultColumns,
        [COLUMN_ID_ID, "start_time"],
      ),
    },
  );

  const excludeFields = useMemo(() => {
    const exclude: string[] = [];

    if (!selectedColumns.includes(COLUMN_EXPERIMENT_ID)) {
      exclude.push("experiment");
    }

    return exclude;
  }, [selectedColumns]);

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useTracesList(
      {
        projectId,
        sorting: sortedColumns,
        filters: effectiveFilters,
        page: page as number,
        size: size as number,
        search: trimmedSearch,
        truncate: truncationEnabled,
        stripAttachments: true,
        fromTime: intervalStart,
        toTime: intervalEnd,
        exclude: excludeFields,
        logsSource,
        visibilityMode: viewConfig.visibilityMode,
      },
      {
        enabled,
        refetchOnMount: false,
        placeholderData: keepPreviousData,
      },
    );

  const { refetch: refetchExportData } = useTracesList(
    {
      projectId,
      sorting: sortedColumns,
      filters: effectiveFilters,
      page: page as number,
      size: size as number,
      search: searchText,
      truncate: false,
      fromTime: intervalStart,
      toTime: intervalEnd,
      exclude: excludeFields,
      logsSource,
      visibilityMode: viewConfig.visibilityMode,
    },
    {
      enabled: false,
      refetchOnMount: "always",
    },
  );

  const { data: statisticData, refetch: refetchStatistic } = useTracesStatistic(
    {
      projectId,
      filters: effectiveFilters,
      search: trimmedSearch,
      fromTime: intervalStart,
      toTime: intervalEnd,
      logsSource,
    },
    {
      enabled,
    },
  );

  const isTableLoading = isPending || isFeedbackScoresPending;

  // A scope narrows the result set just as filters do, so an empty scoped table means "nothing
  // matched", not "nothing recorded yet" — the illustration would be misleading.
  const noData =
    !searchText && chipFilters.length === 0 && scopeFilters.length === 0;

  const handleClearFilters = useCallback(() => {
    setSearch("");
    clearAllChips();
  }, [setSearch, clearAllChips]);

  const rows: Array<Trace> = useMemo(
    () => (data?.content as Trace[]) ?? [],
    [data?.content],
  );

  const showEmptyState =
    !isTableLoading && noData && rows.length === 0 && page === 1;

  const metadataPaths = useMemo(() => {
    const allPaths = rows.reduce<string[]>((acc, row) => {
      if (row.metadata && (isObject(row.metadata) || isArray(row.metadata))) {
        return acc.concat(getJSONPaths(row.metadata, "metadata", []));
      }
      return acc;
    }, []);
    return uniq(allPaths).sort();
  }, [rows]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  const columnsStatistic: ColumnsStatistic = useMemo(
    () => statisticData?.stats ?? [],
    [statisticData],
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    `${storagePrefix}${COLUMNS_ORDER_KEY_SUFFIX}`,
    {
      defaultValue: DEFAULT_TRACES_COLUMNS_ORDER,
    },
  );

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(`${storagePrefix}${COLUMNS_SCORES_ORDER_KEY_SUFFIX}`, {
    defaultValue: [],
  });

  const [metadataColumnsOrder, setMetadataColumnsOrder] = useLocalStorageState<
    string[]
  >(`${storagePrefix}${COLUMNS_METADATA_ORDER_KEY_SUFFIX}`, {
    defaultValue: [COLUMN_METADATA_ID],
  });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(`${storagePrefix}${COLUMNS_WIDTH_KEY_SUFFIX}`, {
    defaultValue: {},
  });

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores?.slice() ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${COLUMN_FEEDBACK_SCORES_ID}.${c.name}`,
        label: c.name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [feedbackScoresData?.scores]);

  const dynamicMetadataColumns = useMemo(() => {
    const paths = metadataPaths ?? [];
    const normalizedPaths = normalizeMetadataPaths(paths);
    return buildDynamicMetadataColumns(normalizedPaths);
  }, [metadataPaths]);

  const dynamicColumnsIds = useMemo(
    () => dynamicScoresColumns.map((c) => c.id),
    [dynamicScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: `${storagePrefix}${DYNAMIC_COLUMNS_KEY_SUFFIX}`,
    // When autoSelectScoreColumns is off (e.g. evaluation-traces view), feedback-score columns stay
    // hidden by default; they remain available in the columns menu via columnSections.
    dynamicColumnsIds: viewConfig.autoSelectScoreColumns
      ? dynamicColumnsIds
      : NO_DYNAMIC_COLUMNS,
    setSelectedColumns,
  });

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
        }) as ColumnData<BaseTraceData>,
    );
  }, [dynamicScoresColumns]);

  const metadataColumnsData = useMemo(() => {
    return dynamicMetadataColumns.map(({ label, id }) => {
      const columnLabel = label.startsWith(".")
        ? `Metadata${label}`
        : `Metadata.${label}`;

      return {
        id,
        label: columnLabel,
        type: COLUMN_TYPE.string,
        sortable: false,
        accessorFn: (row) => {
          const value = get(row, id);

          if (value === undefined || value === null) {
            return "-";
          }

          return value;
        },
        cell: AutodetectCell as never,
      };
    }) as ColumnData<BaseTraceData>[];
  }, [dynamicMetadataColumns]);

  const selectedRows: Array<Trace> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const getDataForExport = useCallback(async (): Promise<Array<Trace>> => {
    const result = await refetchExportData();

    if (result.error) {
      throw result.error;
    }

    if (!result.data?.content) {
      throw new Error("Failed to fetch data");
    }

    const selectedIds = new Set(Object.keys(rowSelection));

    return result.data.content.filter((row) =>
      selectedIds.has(row.id),
    ) as Trace[];
  }, [refetchExportData, rowSelection]);

  const { traceId, handleRowClick, panels } = useTraceThreadPanelsState<Trace>({
    rows,
    type: "trace",
    queryPrefix: TLS_QUERY_PREFIX,
    manageLastSection: true,
    traceDetailsPanelProps: { projectId, container },
    threadDetailsPanelProps: {
      projectId,
      projectName,
      container,
    },
  });

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Trace>(),
      ...convertColumnDataToColumn<BaseTraceData, Trace>(COLUMN_DATA, {
        columnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      ...convertColumnDataToColumn<BaseTraceData, Trace>(scoresColumnsData, {
        columnsOrder: scoresColumnsOrder,
        selectedColumns,
        sortableColumns: sortableBy,
      }),
      ...convertColumnDataToColumn<BaseTraceData, Trace>(
        [...METADATA_MAIN_COLUMN_DATA, ...metadataColumnsData],
        {
          columnsOrder: metadataColumnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
    ];
  }, [
    sortableBy,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
    metadataColumnsData,
    metadataColumnsOrder,
  ]);

  const virtualization = useMemo(
    () => getVirtualizationConfig(columns.length, rows.length || (size ?? 0)),
    [columns.length, rows.length, size],
  );

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

  const activeRowId = traceId;

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
    const sections: {
      title: string;
      columns: typeof scoresColumnsData;
      order: string[];
      onOrderChange: (order: string[]) => void;
    }[] = [
      {
        title: "Feedback scores",
        columns: scoresColumnsData,
        order: scoresColumnsOrder,
        onOrderChange: setScoresColumnsOrder,
      },
    ];

    const allMetadataColumns = [
      ...METADATA_MAIN_COLUMN_DATA,
      ...metadataColumnsData,
    ];

    if (allMetadataColumns.length > 0) {
      sections.push({
        title: "Metadata",
        columns: allMetadataColumns,
        order: metadataColumnsOrder,
        onOrderChange: setMetadataColumnsOrder,
      });
    }

    return sections;
  }, [
    scoresColumnsData,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    metadataColumnsData,
    metadataColumnsOrder,
    setMetadataColumnsOrder,
  ]);

  // 24px controls throughout, matching the optimization pages (runs list and trial sidebar) — the
  // house size for a chip-bar toolbar on both page and overlay surfaces.
  const controls = (
    <div className="flex shrink-0 items-center gap-2">
      {viewConfig.showTableControls && (
        <DataTableRowHeightSelector
          type={height as ROW_HEIGHT}
          setType={setHeight}
          size="icon-2xs"
        />
      )}
      <ColumnsButton
        columns={COLUMN_DATA}
        selectedColumns={selectedColumns}
        onSelectionChange={setSelectedColumns}
        order={columnsOrder}
        onOrderChange={setColumnsOrder}
        sections={columnSections}
        layout="labeled"
        size="2xs"
        excludeFromSelectAll={
          metadataColumnsData.length > 0
            ? metadataColumnsData.map((col) => col.id)
            : []
        }
      ></ColumnsButton>
      {viewConfig.showTableControls && (
        <>
          <Separator orientation="vertical" className="mx-[2px] h-4" />
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
            triggerClassName="h-6"
          />
          <Separator orientation="vertical" className="mx-[2px] h-4" />
          <RefreshButton
            tooltip="Refresh traces list"
            size="icon-2xs"
            isFetching={isFetching}
            onRefresh={() => {
              refetch();
              refetchStatistic();
            }}
          />
        </>
      )}
    </div>
  );

  const hasSelection = selectedRows.length > 0;

  const selectionBar = (
    <SelectionActionBar
      selectedCount={selectedRows.length}
      onDeselectAll={() => setRowSelection({})}
    >
      <TracesActionsPanel
        projectId={projectId}
        projectName={projectName}
        getDataForExport={getDataForExport}
        selectedRows={selectedRows}
        columnsToExport={columnsToExport}
        type={type}
        hideEvaluate
        buttonVariant="ghostInverted"
        buttonSize="2xs"
      />
    </SelectionActionBar>
  );

  const chipBar = (
    <FilterChipBar
      chipsPinned={chipsPinned}
      chipsUnpinned={chipsUnpinned}
      values={chipValues}
      managerOpen={chipManagerOpen}
      onManagerOpenChange={setChipManagerOpen}
      onApplyValue={applyChipValue}
      onClearValue={clearChipValue}
      onPinChip={pinChip}
      onUnpinChip={unpinChip}
      onClearAll={clearAllChips}
      openChipId={openChipId}
      onOpenChipIdChange={setOpenChipId}
      prefix={
        <div className="flex shrink-0 items-center gap-2">
          <SearchInput
            searchText={searchText}
            setSearchText={setSearch}
            placeholder="Search by anything"
            className="w-[200px] shrink-0"
            dimension="xs"
          />
          {scopeLabel && (
            <TooltipWrapper content={scopeTooltip}>
              <Tag
                size="md"
                variant="gray"
                className="flex max-w-[260px] items-center gap-1"
              >
                <Lock className="size-3 shrink-0" />
                <span className="truncate">{scopeLabel}</span>
              </Tag>
            </TooltipWrapper>
          )}
        </div>
      }
    />
  );

  const toolbarRow = hasSelection ? (
    <div className="w-full">{selectionBar}</div>
  ) : (
    <>
      <div className="min-w-0 flex-1">{chipBar}</div>
      {controls}
    </>
  );

  const metricsSummary = viewConfig.showMetricsSummary ? (
    <MetricsSummary
      projectId={projectId}
      entityType="traces"
      countLabel="Traces"
      filters={effectiveFilters}
      intervalStart={intervalStart}
      intervalEnd={intervalEnd}
      dateRange={dateRange}
      logsSource={logsSource}
    />
  ) : null;

  const renderTable = (
    extraProps: {
      TableWrapper?: React.FC<DataTableWrapperProps>;
      stickyHeader?: boolean;
    } = {},
  ) => (
    <DataTableStateHandler
      isLoading={isTableLoading}
      isEmpty={showEmptyState}
      emptyState={
        <DataTableEmptyContent
          title="There are no traces yet"
          description="Traces will appear here once your agent starts receiving requests."
          lightImageUrl={emptyLogsLightUrl}
          darkImageUrl={emptyLogsDarkUrl}
        />
      }
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
        columnPinning={DEFAULT_TRACES_COLUMN_PINNING}
        noData={
          <DataTableNoMatchingData
            onClearFilters={
              search || chipFilters.length > 0 ? handleClearFilters : undefined
            }
          />
        }
        showLoadingOverlay={isPlaceholderData && isFetching}
        TableBody={DataTableVirtualBody}
        columnVirtualization={virtualization}
        rowVirtualization={virtualization}
        {...extraProps}
      />
    </DataTableStateHandler>
  );

  const pagination = (
    <DataTablePagination
      page={page as number}
      pageChange={setPage}
      size={size as number}
      sizeChange={setSize}
      total={data?.total ?? 0}
      supportsTruncation
      truncationEnabled={truncationEnabled}
    />
  );

  // Page hosts (the experiment Logs tab) scroll with the page and pin their toolbar rows through
  // the page's sticky containers, so the view must not open a scroll context of its own.
  if (isPageLayout) {
    return (
      <div className={className}>
        {metricsSummary && (
          <PageBodyStickyContainer
            className="pt-4"
            direction="horizontal"
            limitWidth
          >
            {metricsSummary}
          </PageBodyStickyContainer>
        )}
        {/* One row directly under the tabs, laid out like the optimization runs toolbar: the chip
            bar takes the remaining width and wraps within itself, so the controls stay right- and
            top-aligned rather than being pushed onto a second line. The negative top margin
            cancels TabsContent's own gap, the way the sibling tabs do. */}
        <PageBodyStickyContainer
          className="-mt-4 flex items-start gap-2 py-4"
          direction="bidirectional"
          limitWidth
        >
          {toolbarRow}
        </PageBodyStickyContainer>
        {renderTable({
          TableWrapper: PageBodyStickyTableWrapper,
          stickyHeader: true,
        })}
        <PageBodyStickyContainer
          className="py-4"
          direction="horizontal"
          limitWidth
        >
          {pagination}
        </PageBodyStickyContainer>
        {panels}
      </div>
    );
  }

  return (
    <>
      <div className={cn("flex min-h-0 flex-1 flex-col", className)}>
        {metricsSummary && <div className="px-6 pt-4">{metricsSummary}</div>}
        {/* Same single row as the page layout. */}
        <div className="flex items-start gap-2 px-6 py-4">{toolbarRow}</div>
        <TableScrollContainer className="border-b px-6">
          {renderTable({ TableWrapper: ScrollTableWrapper })}
        </TableScrollContainer>
        <div className="border-t px-6 py-3">{pagination}</div>
      </div>
      {panels}
    </>
  );
};

export default TraceLogsView;
