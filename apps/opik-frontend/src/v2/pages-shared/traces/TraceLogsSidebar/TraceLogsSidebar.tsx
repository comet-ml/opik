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
} from "@/v2/pages-shared/traces/MetricDateRangeSelect";

import useTracesList from "@/api/traces/useTracesList";
import { TRACE_DATA_TYPE } from "@/constants/traces";
import useTracesFeedbackScoresNames from "@/api/traces/useTracesFeedbackScoresNames";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_EXPERIMENT_ID,
  COLUMN_EXPERIMENT_IDS,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ColumnsStatistic,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
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
import { getJSONPaths } from "@/lib/utils";
import { generateSelectColumDef } from "@/shared/DataTable/utils";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import TracesActionsPanel from "@/v2/pages-shared/traces/TracesActionsPanel/TracesActionsPanel";
import MetricsSummary from "@/v2/pages-shared/traces/MetricsSummary/MetricsSummary";
import { Separator } from "@/ui/separator";
import { Sheet, SheetContent, SheetTopBar } from "@/ui/sheet";
import { Tag } from "@/ui/tag";
import { Lock } from "lucide-react";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import RefreshButton from "@/shared/RefreshButton/RefreshButton";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableEmptyContent from "@/shared/DataTableNoData/DataTableEmptyContent";
import DataTableNoMatchingData from "@/shared/DataTableNoData/DataTableNoMatchingData";
import emptyLogsLightUrl from "/images/empty-logs-light.svg";
import emptyLogsDarkUrl from "/images/empty-logs-dark.svg";
import DataTablePagination from "@/shared/DataTablePagination/DataTablePagination";
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
import TracesOrSpansPathsAutocomplete from "@/v2/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/v2/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import ErrorTypeAutocomplete from "@/v2/pages-shared/traces/ErrorTypeAutocomplete/ErrorTypeAutocomplete";
import ExperimentsSelectBoxFilterWrapper from "@/v2/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBoxFilterWrapper";
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

const getRowId = (d: Trace) => d.id;

const TLS_STORAGE_PREFIX = "tls-traces-";
export const TLS_QUERY_PREFIX = "tls_";

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

// Per-view behavior of the sidebar. Defaults serve the entity-scoped logs views (experiments,
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
};

export const DEFAULT_TRACE_LOGS_VIEW_CONFIG: TraceLogsViewConfig = {
  storageNamespace: "",
  defaultColumns: DEFAULT_TRACES_COLUMNS,
  autoSelectScoreColumns: true,
  showMetricsSummary: false,
  visibilityMode: TRACE_VISIBILITY_MODE.all,
};

// Stable empty reference for the "don't auto-select score columns" case (keeps hook deps steady).
const NO_DYNAMIC_COLUMNS: string[] = [];

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

const FILTERS_COLUMN_DATA: ColumnData<BaseTraceData>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS.flatMap((col) =>
    col.id === "error_info"
      ? [
          col,
          {
            id: "error_type",
            label: "Error type",
            type: COLUMN_TYPE.string,
          },
        ]
      : [col],
  ),
  {
    id: "thread_id",
    label: "Thread ID",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_EXPERIMENT_IDS,
    label: "Experiment",
    type: COLUMN_TYPE.string,
  },
  {
    id: "annotation_queue_ids",
    label: "Annotation queue ID",
    type: COLUMN_TYPE.list,
  },
  {
    id: "llm_span_count",
    label: "LLM calls count",
    type: COLUMN_TYPE.number,
  },
  {
    id: COLUMN_METADATA_ID,
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: COLUMN_CUSTOM_ID,
    label: "Custom filter",
    type: COLUMN_TYPE.dictionary,
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

type TraceLogsSidebarProps = {
  open: boolean;
  onClose: () => void;
  projectId: string;
  projectName?: string;
  logsSource?: LOGS_SOURCE;
  title?: string;
  viewConfig?: TraceLogsViewConfig;
};

const TraceLogsSidebar: React.FunctionComponent<TraceLogsSidebarProps> = ({
  open,
  onClose,
  projectId,
  projectName = "",
  logsSource,
  title = "Logs",
  viewConfig = DEFAULT_TRACE_LOGS_VIEW_CONFIG,
}) => {
  const type = TRACE_DATA_TYPE.traces;
  const truncationEnabled = useTruncationEnabled();

  const storagePrefix = `${TLS_STORAGE_PREFIX}${viewConfig.storageNamespace}`;

  const {
    dateRange,
    handleDateRangeChange,
    intervalStart,
    intervalEnd,
    minDate,
    maxDate,
  } = useMetricDateRangeWithQueryAndStorage();

  const [search = "", setSearch] = useQueryParam(
    `${TLS_QUERY_PREFIX}search`,
    StringParam,
    {
      updateType: "replaceIn",
    },
  );
  const trimmedSearch = (search as string).trim().toLowerCase();

  const [sheetContentRef, setSheetContentRef] = useState<HTMLDivElement | null>(
    null,
  );

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

  const [filters = [], setFilters] = useQueryParam(
    `${TLS_QUERY_PREFIX}filters`,
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  // Locked scope (e.g. a single evaluator's Evaluation traces): always constrains the query and is
  // never shown in the editable filter bar, so it can't be changed or removed. User filters layer
  // on top with AND semantics.
  const [scopeFilters = []] = useQueryParam(
    `${TLS_QUERY_PREFIX}scope`,
    JsonParam,
  );
  const [scopeLabel] = useQueryParam(
    `${TLS_QUERY_PREFIX}scopeLabel`,
    StringParam,
  );
  const effectiveFilters = useMemo(
    () => [...(scopeFilters ?? []), ...(filters ?? [])],
    [scopeFilters, filters],
  );

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: `${storagePrefix}${COLUMNS_SORT_KEY_SUFFIX}`,
    queryKey: `${TLS_QUERY_PREFIX}sort`,
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
        [COLUMN_CUSTOM_ID]: {
          keyComponent: TracesOrSpansPathsAutocomplete,
          keyComponentProps: {
            rootKeys: ["input", "output"],
            projectId,
            type,
            placeholder: "key",
            excludeRoot: false,
          },
          validateFilter: (filter: Filter) => {
            if (
              filter.key &&
              filter.value &&
              !CUSTOM_FILTER_VALIDATION_REGEXP.test(filter.key)
            ) {
              return `Key is invalid, it should begin with "input", or "output" and follow this format: "input.[PATH]" For example: "input.message" `;
            }
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
        error_type: {
          keyComponent: ErrorTypeAutocomplete,
          keyComponentProps: {
            projectId,
            type,
          },
        },
        [COLUMN_EXPERIMENT_IDS]: {
          keyComponent: ExperimentsSelectBoxFilterWrapper as never,
          keyComponentProps: { projectId },
          operators: [{ label: "is one of", value: "in" }],
          defaultOperator: "in",
        },
      },
    }),
    [projectId, type],
  );

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
        enabled: open,
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
      search: search as string,
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
      enabled: open,
    },
  );

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useTracesFeedbackScoresNames(
      {
        projectId,
      },
      {
        enabled: open,
      },
    );

  const isTableLoading = isPending || isFeedbackScoresPending;

  const noData = !search && filters.length === 0;

  const handleClearFilters = useCallback(() => {
    setSearch("");
    setFilters([]);
  }, [setSearch, setFilters]);

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

    const allRows = result.data.content;
    const selectedIds = Object.keys(rowSelection);

    return allRows.filter((row) => selectedIds.includes(row.id)) as Trace[];
  }, [refetchExportData, rowSelection]);

  const {
    traceId,
    handleRowClick,
    handleClose: clearPanelsState,
    panels,
  } = useTraceThreadPanelsState<Trace>({
    rows,
    type: "trace",
    queryPrefix: TLS_QUERY_PREFIX,
    manageLastSection: true,
    traceDetailsPanelProps: { projectId, container: sheetContentRef },
    threadDetailsPanelProps: {
      projectId,
      projectName,
      container: sheetContentRef,
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

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen) {
        clearPanelsState();
        onClose();
      }
    },
    [clearPanelsState, onClose],
  );

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

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        ref={setSheetContentRef}
        className="flex w-screen flex-col shadow-none sm:max-w-full"
        header={<SheetTopBar variant="info" title={title} />}
        onEscapeKeyDown={(e) => {
          if (traceId) {
            e.preventDefault();
          }
        }}
      >
        <div className="flex min-h-0 flex-1 flex-col">
          {viewConfig.showMetricsSummary && (
            <div className="px-6 pt-4">
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
            </div>
          )}
          <div className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 px-6 py-4">
            <div className="flex items-center gap-2">
              <SearchInput
                searchText={search as string}
                setSearchText={setSearch}
                placeholder="Search traces..."
                className="w-[320px]"
                dimension="sm"
              />
              <FiltersButton
                columns={FILTERS_COLUMN_DATA}
                config={filtersConfig as never}
                filters={filters}
                onChange={setFilters}
                layout="icon"
              />
              {scopeLabel && (
                <TooltipWrapper content="These traces are locked to this evaluator and can't be changed via filters">
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
            <div className="flex items-center gap-2">
              <TracesActionsPanel
                projectId={projectId}
                projectName={projectName}
                getDataForExport={getDataForExport}
                selectedRows={selectedRows}
                columnsToExport={columnsToExport}
                type={type}
                hideEvaluate
              />
              <Separator orientation="vertical" className="mx-2 h-4" />
              <MetricDateRangeSelect
                value={dateRange}
                onChangeValue={handleDateRangeChange}
                minDate={minDate}
                maxDate={maxDate}
              />
              <RefreshButton
                tooltip="Refresh traces list"
                isFetching={isFetching}
                onRefresh={() => {
                  refetch();
                  refetchStatistic();
                }}
              />
              <DataTableRowHeightSelector
                type={height as ROW_HEIGHT}
                setType={setHeight}
              />
              <ColumnsButton
                columns={COLUMN_DATA}
                selectedColumns={selectedColumns}
                onSelectionChange={setSelectedColumns}
                order={columnsOrder}
                onOrderChange={setColumnsOrder}
                sections={columnSections}
                excludeFromSelectAll={
                  metadataColumnsData.length > 0
                    ? metadataColumnsData.map((col) => col.id)
                    : []
                }
              ></ColumnsButton>
            </div>
          </div>

          <div className="min-h-0 flex-1 overflow-auto border-b px-6">
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
                      search || filters.length > 0
                        ? handleClearFilters
                        : undefined
                    }
                  />
                }
                showLoadingOverlay={isPlaceholderData && isFetching}
              />
            </DataTableStateHandler>
          </div>

          <div className="border-t px-6 py-3">
            <DataTablePagination
              page={page as number}
              pageChange={setPage}
              size={size as number}
              sizeChange={setSize}
              total={data?.total ?? 0}
              supportsTruncation
              truncationEnabled={truncationEnabled}
            />
          </div>
        </div>
        {panels}
      </SheetContent>
    </Sheet>
  );
};

export default TraceLogsSidebar;
