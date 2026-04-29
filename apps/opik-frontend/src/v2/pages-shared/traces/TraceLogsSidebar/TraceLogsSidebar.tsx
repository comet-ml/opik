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
import { RotateCw, Undo2, X } from "lucide-react";
import findIndex from "lodash/findIndex";
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
import { BaseTraceData, Trace, LOGS_SOURCE } from "@/types/traces";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import { getJSONPaths } from "@/lib/utils";
import { generateSelectColumDef } from "@/shared/DataTable/utils";
import SearchInput from "@/shared/SearchInput/SearchInput";
import FiltersButton from "@/shared/FiltersButton/FiltersButton";
import TracesActionsPanel from "@/v2/pages-shared/traces/TracesActionsPanel/TracesActionsPanel";
import { Separator } from "@/ui/separator";
import { Button } from "@/ui/button";
import { Sheet, SheetContent, SheetTitle } from "@/ui/sheet";
import DataTableRowHeightSelector from "@/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/shared/DataTable/DataTable";
import DataTableNoData from "@/shared/DataTableNoData/DataTableNoData";
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
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import TraceDetailsPanel from "@/v2/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import TracesOrSpansPathsAutocomplete from "@/v2/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/v2/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import ErrorTypeAutocomplete from "@/v2/pages-shared/traces/ErrorTypeAutocomplete/ErrorTypeAutocomplete";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import TimeCell from "@/shared/DataTableCells/TimeCell";
import useTracesStatistic from "@/api/traces/useTracesStatistic";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/v2/constants/explainers";
import {
  DetailsActionSectionParam,
  DetailsActionSectionValue,
} from "@/v2/pages-shared/traces/DetailsActionSection";
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
    id: COLUMN_EXPERIMENT_ID,
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
  backLabel?: string;
};

const TraceLogsSidebar: React.FunctionComponent<TraceLogsSidebarProps> = ({
  open,
  onClose,
  projectId,
  projectName = "",
  logsSource,
  title = "Logs",
  backLabel = "Back",
}) => {
  const type = TRACE_DATA_TYPE.traces;
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

  const [traceId = "", setTraceId] = useQueryParam(
    `${TLS_QUERY_PREFIX}trace`,
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [spanId = "", setSpanId] = useQueryParam(
    `${TLS_QUERY_PREFIX}span`,
    StringParam,
    {
      updateType: "replaceIn",
    },
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
    localStorageKey: `${TLS_STORAGE_PREFIX}${PAGINATION_SIZE_KEY_SUFFIX}`,
    queryKey: `${TLS_QUERY_PREFIX}size`,
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [, setLastSection] = useQueryParam(
    `${TLS_QUERY_PREFIX}lastSection`,
    DetailsActionSectionParam,
    {
      updateType: "replaceIn",
    },
  );

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `${TLS_STORAGE_PREFIX}${ROW_HEIGHT_KEY_SUFFIX}`,
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

  const [sortedColumns, setSortedColumns] = useQueryParamAndLocalStorageState<
    ColumnSort[]
  >({
    localStorageKey: `${TLS_STORAGE_PREFIX}${COLUMNS_SORT_KEY_SUFFIX}`,
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
      },
    }),
    [projectId, type],
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${TLS_STORAGE_PREFIX}${SELECTED_COLUMNS_KEY_V2_SUFFIX}`,
    {
      defaultValue: migrateSelectedColumns(
        `${TLS_STORAGE_PREFIX}selected-columns`,
        DEFAULT_TRACES_COLUMNS,
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
        filters: filters,
        page: page as number,
        size: size as number,
        search: trimmedSearch,
        truncate: truncationEnabled,
        fromTime: intervalStart,
        toTime: intervalEnd,
        exclude: excludeFields,
        logsSource,
      },
      {
        enabled: open,
        refetchOnMount: false,
      },
    );

  const { refetch: refetchExportData } = useTracesList(
    {
      projectId,
      sorting: sortedColumns,
      filters: filters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: false,
      fromTime: intervalStart,
      toTime: intervalEnd,
      exclude: excludeFields,
      logsSource,
    },
    {
      enabled: false,
      refetchOnMount: "always",
    },
  );

  const { data: statisticData, refetch: refetchStatistic } = useTracesStatistic(
    {
      projectId,
      filters: filters,
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
  const noDataText = noData ? "There are no traces yet" : "No search results";

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
    `${TLS_STORAGE_PREFIX}${COLUMNS_ORDER_KEY_SUFFIX}`,
    {
      defaultValue: DEFAULT_TRACES_COLUMNS_ORDER,
    },
  );

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(`${TLS_STORAGE_PREFIX}${COLUMNS_SCORES_ORDER_KEY_SUFFIX}`, {
    defaultValue: [],
  });

  const [metadataColumnsOrder, setMetadataColumnsOrder] = useLocalStorageState<
    string[]
  >(`${TLS_STORAGE_PREFIX}${COLUMNS_METADATA_ORDER_KEY_SUFFIX}`, {
    defaultValue: [COLUMN_METADATA_ID],
  });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(`${TLS_STORAGE_PREFIX}${COLUMNS_WIDTH_KEY_SUFFIX}`, {
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
    dynamicColumnsKey: `${TLS_STORAGE_PREFIX}${DYNAMIC_COLUMNS_KEY_SUFFIX}`,
    dynamicColumnsIds,
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

  const handleRowClick = useCallback(
    (row?: Trace, lastSection?: DetailsActionSectionValue) => {
      if (!row) return;
      setTraceId((state) => (row.id === state ? "" : row.id));
      setSpanId("");

      if (lastSection) {
        setLastSection(lastSection);
      }
    },
    [setTraceId, setSpanId, setLastSection],
  );

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

  const handleOpenChange = useCallback(
    (isOpen: boolean) => {
      if (!isOpen) {
        onClose();
      }
    },
    [onClose],
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

  const sheetHeader = (
    <>
      <SheetTitle className="sr-only">{title}</SheetTitle>
      <div className="flex items-center justify-between border-b px-5 py-3">
        <Button variant="outline" size="2xs" onClick={onClose}>
          <Undo2 className="mr-1 size-3" />
          {backLabel}
        </Button>
        <Button variant="ghost" size="icon-sm" onClick={onClose}>
          <X />
        </Button>
      </div>
    </>
  );

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent
        ref={setSheetContentRef}
        className="flex w-screen flex-col shadow-none sm:max-w-full"
        header={sheetHeader}
        onEscapeKeyDown={(e) => {
          if (traceId) {
            e.preventDefault();
          }
        }}
      >
        <div className="flex min-h-0 flex-1 flex-col">
          <div className="px-6 pb-1 pt-4">
            <h2 className="comet-title-xxs">{title}</h2>
          </div>

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
              <TooltipWrapper content="Refresh traces list">
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
              emptyState={<DataTableNoData title="There are no traces yet" />}
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
                noData={<DataTableNoData title={noDataText} />}
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
        <TraceDetailsPanel
          projectId={projectId}
          traceId={traceId!}
          spanId={spanId!}
          setSpanId={setSpanId}
          hasPreviousRow={hasPrevious}
          hasNextRow={hasNext}
          open={Boolean(traceId)}
          onClose={handleClose}
          onRowChange={handleRowChange}
          container={sheetContentRef}
        />
      </SheetContent>
    </Sheet>
  );
};

export default TraceLogsSidebar;
