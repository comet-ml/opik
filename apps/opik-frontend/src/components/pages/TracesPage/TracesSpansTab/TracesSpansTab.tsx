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
import isArray from "lodash/isArray";
import get from "lodash/get";
import uniq from "lodash/uniq";
import {
  useMetricDateRangeWithQueryAndStorage,
  MetricDateRangeSelect,
} from "@/components/pages-shared/traces/MetricDateRangeSelect";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import useTracesOrSpansScoresColumns from "@/hooks/useTracesOrSpansScoresColumns";
import {
  COLUMN_COMMENTS_ID,
  COLUMN_EXPERIMENT_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_SPAN_FEEDBACK_SCORES_ID,
  COLUMN_GUARDRAIL_STATISTIC_ID,
  COLUMN_GUARDRAILS_ID,
  COLUMN_ID_ID,
  COLUMN_CUSTOM_ID,
  COLUMN_METADATA_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ColumnsStatistic,
  DropdownOption,
  DynamicColumn,
  HeaderIconType,
  ROW_HEIGHT,
} from "@/types/shared";
import { CUSTOM_FILTER_VALIDATION_REGEXP } from "@/constants/filters";
import {
  normalizeMetadataPaths,
  buildDynamicMetadataColumns,
} from "@/lib/metadata";
import { BaseTraceData, Span, SPAN_TYPE, Trace } from "@/types/traces";
import { convertColumnDataToColumn, migrateSelectedColumns } from "@/lib/table";
import { getJSONPaths } from "@/lib/utils";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
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
import ResourceCell from "@/components/shared/DataTableCells/ResourceCell";
import { RESOURCE_TYPE } from "@/components/shared/ResourceLink/ResourceLink";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import ErrorCell from "@/components/shared/DataTableCells/ErrorCell";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import FeedbackScoreCell from "@/components/shared/DataTableCells/FeedbackScoreCell";
import PrettyCell from "@/components/shared/DataTableCells/PrettyCell";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import DataTableStateHandler from "@/components/shared/DataTableStateHandler/DataTableStateHandler";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ThreadDetailsPanel from "@/components/pages-shared/traces/ThreadDetailsPanel/ThreadDetailsPanel";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import TracesOrSpansPathsAutocomplete from "@/components/pages-shared/traces/TracesOrSpansPathsAutocomplete/TracesOrSpansPathsAutocomplete";
import TracesOrSpansFeedbackScoresSelect from "@/components/pages-shared/traces/TracesOrSpansFeedbackScoresSelect/TracesOrSpansFeedbackScoresSelect";
import ExperimentsSelectBox from "@/components/pages-shared/experiments/ExperimentsSelectBox/ExperimentsSelectBox";
import { formatDate, formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import useTracesOrSpansStatistic from "@/hooks/useTracesOrSpansStatistic";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import { useIsFeatureEnabled } from "@/components/feature-toggles-provider";
import { FeatureToggleKeys } from "@/types/feature-toggles";
import GuardrailsCell from "@/components/shared/DataTableCells/GuardrailsCell";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import {
  DetailsActionSection,
  DetailsActionSectionParam,
  DetailsActionSectionValue,
} from "@/components/pages-shared/traces/DetailsActionSection";
import { GuardrailResult } from "@/types/guardrails";
import { SelectItem } from "@/components/ui/select";
import BaseTraceDataTypeIcon from "@/components/pages-shared/traces/TraceDetailsPanel/BaseTraceDataTypeIcon";
import { SPAN_TYPE_LABELS_MAP } from "@/constants/traces";
import SpanTypeCell from "@/components/shared/DataTableCells/SpanTypeCell";
import { Filter, FilterOperator } from "@/types/filters";
import {
  USER_FEEDBACK_COLUMN_ID,
  USER_FEEDBACK_NAME,
} from "@/constants/shared";
import { useTruncationEnabled } from "@/components/server-sync-provider";
import LogsTypeToggle from "@/components/pages/TracesPage/LogsTab/LogsTypeToggle";
import { LOGS_TYPE } from "@/constants/traces";

const getRowId = (d: Trace | Span) => d.id;

const REFETCH_INTERVAL = 30000;

const SPAN_FEEDBACK_SCORE_SUFFIX = " (span)";

/**
 * Formats a score name with the span suffix for display in column labels
 */
const formatSpanScoreLabel = (scoreName: string): string => {
  return `${scoreName}${SPAN_FEEDBACK_SCORE_SUFFIX}`;
};

/**
 * Extracts the score name from a label by removing the span suffix
 */
const parseSpanScoreName = (label: string): string => {
  return label.replace(SPAN_FEEDBACK_SCORE_SUFFIX, "");
};

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

const DEFAULT_TRACES_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

const DEFAULT_TRACES_PAGE_COLUMNS: string[] = [
  COLUMN_ID_ID,
  "name",
  "start_time",
  "input",
  "output",
  "duration",
  COLUMN_COMMENTS_ID,
  USER_FEEDBACK_COLUMN_ID,
];

const SELECTED_COLUMNS_KEY_SUFFIX = "selected-columns";
const SELECTED_COLUMNS_KEY_V2_SUFFIX = `${SELECTED_COLUMNS_KEY_SUFFIX}-v2`;
const COLUMNS_WIDTH_KEY_SUFFIX = "columns-width";
const COLUMNS_ORDER_KEY_SUFFIX = "columns-order";
const COLUMNS_SORT_KEY_SUFFIX = "columns-sort";
const COLUMNS_SCORES_ORDER_KEY_SUFFIX = "scores-columns-order";
const DYNAMIC_COLUMNS_KEY_SUFFIX = "dynamic-columns";
const PAGINATION_SIZE_KEY_SUFFIX = "pagination-size";
const ROW_HEIGHT_KEY_SUFFIX = "row-height";

type TracesSpansTabProps = {
  type: TRACE_DATA_TYPE;
  projectId: string;
  projectName: string;
  logsType: LOGS_TYPE;
  onLogsTypeChange: (type: LOGS_TYPE) => void;
};

export const TracesSpansTab: React.FC<TracesSpansTabProps> = ({
  type,
  logsType,
  onLogsTypeChange,
  projectId,
  projectName,
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
    `${type}_search`,
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

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size, setSize] = useQueryParamAndLocalStorageState<
    number | null | undefined
  >({
    localStorageKey: `${type}-${PAGINATION_SIZE_KEY_SUFFIX}`,
    queryKey: "size",
    defaultValue: 100,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [, setLastSection] = useQueryParam(
    "lastSection",
    DetailsActionSectionParam,
    {
      updateType: "replaceIn",
    },
  );

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: `${type}-${ROW_HEIGHT_KEY_SUFFIX}`,
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
    localStorageKey: `${type}-${COLUMNS_SORT_KEY_SUFFIX}`,
    queryKey: `${type}_sorting`,
    defaultValue: [],
    queryParamConfig: JsonParam,
  });

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        type: {
          keyComponentProps: {
            options: [
              {
                value: SPAN_TYPE.general,
                label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.general],
              },
              {
                value: SPAN_TYPE.tool,
                label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.tool],
              },
              {
                value: SPAN_TYPE.llm,
                label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.llm],
              },
              ...(isGuardrailsEnabled
                ? [
                    {
                      value: SPAN_TYPE.guardrail,
                      label: SPAN_TYPE_LABELS_MAP[SPAN_TYPE.guardrail],
                    },
                  ]
                : []),
            ],
            placeholder: "Select type",
            renderOption: (option: DropdownOption<SPAN_TYPE>) => {
              return (
                <SelectItem
                  key={option.value}
                  value={option.value}
                  withoutCheck
                  wrapperAsChild={true}
                >
                  <div className="flex w-full items-center gap-1.5">
                    <BaseTraceDataTypeIcon type={option.value} />
                    {option.label}
                  </div>
                </SelectItem>
              );
            },
          },
        },
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
        ...(type === TRACE_DATA_TYPE.traces
          ? {
              [COLUMN_SPAN_FEEDBACK_SCORES_ID]: {
                keyComponent: TracesOrSpansFeedbackScoresSelect,
                keyComponentProps: {
                  projectId,
                  type: TRACE_DATA_TYPE.spans,
                  placeholder: "Select span score",
                },
              },
              [COLUMN_EXPERIMENT_ID]: {
                keyComponent: ExperimentsSelectBox,
                keyComponentProps: {
                  className: "w-full min-w-72",
                  projectId,
                },
                defaultOperator: "=" as FilterOperator,
                operators: [{ label: "=", value: "=" as FilterOperator }],
              },
            }
          : {}),
        [COLUMN_GUARDRAILS_ID]: {
          keyComponentProps: {
            options: [
              { value: GuardrailResult.FAILED, label: "Failed" },
              { value: GuardrailResult.PASSED, label: "Passed" },
            ],
            placeholder: "Status",
          },
        },
      },
    }),
    [projectId, type, isGuardrailsEnabled],
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});
  const [isTableDataEnabled, setIsTableDataEnabled] = useState(false);

  // Declare selectedColumns early so it can be used in excludeFields computation
  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    `${type}-${SELECTED_COLUMNS_KEY_V2_SUFFIX}`,
    {
      defaultValue: migrateSelectedColumns(
        `${type}-${SELECTED_COLUMNS_KEY_SUFFIX}`,
        DEFAULT_TRACES_PAGE_COLUMNS,
        [COLUMN_ID_ID, "start_time"],
      ),
    },
  );

  // Compute exclude parameter based on visible columns and data type
  const excludeFields = useMemo(() => {
    const exclude: string[] = [];

    // Only exclude experiment field for traces (not spans) when column is not visible
    if (
      type === TRACE_DATA_TYPE.traces &&
      !selectedColumns.includes(COLUMN_EXPERIMENT_ID)
    ) {
      exclude.push("experiment");
    }

    return exclude;
  }, [type, selectedColumns]);

  // Enable table data loading after initial render to allow users to change the date filter
  React.useEffect(() => {
    const timer = setTimeout(() => {
      setIsTableDataEnabled(true);
    }, 0);
    return () => clearTimeout(timer);
  }, []);

  const { data, isPending, isPlaceholderData, isFetching, refetch } =
    useTracesOrSpansList(
      {
        projectId,
        type: type as TRACE_DATA_TYPE,
        sorting: sortedColumns,
        filters,
        page: page as number,
        size: size as number,
        search: search as string,
        truncate: truncationEnabled,
        fromTime: intervalStart,
        toTime: intervalEnd,
        exclude: excludeFields,
      },
      {
        enabled: isTableDataEnabled,
        refetchInterval: REFETCH_INTERVAL,
        refetchOnMount: false,
      },
    );

  const { refetch: refetchExportData } = useTracesOrSpansList(
    {
      projectId,
      type: type as TRACE_DATA_TYPE,
      sorting: sortedColumns,
      filters,
      page: page as number,
      size: size as number,
      search: search as string,
      truncate: false,
      fromTime: intervalStart,
      toTime: intervalEnd,
      exclude: excludeFields,
    },
    {
      enabled: false,
      refetchOnMount: "always",
    },
  );

  const { data: statisticData, refetch: refetchStatistic } =
    useTracesOrSpansStatistic(
      {
        projectId,
        type: type as TRACE_DATA_TYPE,
        filters,
        search: search as string,
        fromTime: intervalStart,
        toTime: intervalEnd,
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

  const {
    data: spanFeedbackScoresData,
    isPending: isSpanFeedbackScoresPending,
  } = useTracesOrSpansScoresColumns(
    {
      projectId,
      type: TRACE_DATA_TYPE.spans,
    },
    {
      enabled: type === TRACE_DATA_TYPE.traces,
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const isTableLoading =
    isPending || isFeedbackScoresPending || isSpanFeedbackScoresPending;

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? `There are no ${type === TRACE_DATA_TYPE.traces ? "traces" : "spans"} yet`
    : "No search results";

  const rows: Array<Span | Trace> = useMemo(
    () => data?.content ?? [],
    [data?.content],
  );

  const showEmptyState =
    !isTableLoading && noData && rows.length === 0 && page === 1;

  // Extract metadata paths directly from loaded traces/spans data
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
    `${type}-${COLUMNS_ORDER_KEY_SUFFIX}`,
    {
      defaultValue: [],
    },
  );

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(`${type}-${COLUMNS_SCORES_ORDER_KEY_SUFFIX}`, {
    defaultValue: [],
  });

  const [metadataColumnsOrder, setMetadataColumnsOrder] = useLocalStorageState<
    string[]
  >(`${type}-metadata-columns-order`, {
    defaultValue: [],
  });
  const [metadataMainColumnOrder, setMetadataMainColumnOrder] =
    useLocalStorageState<string[]>(`${type}-metadata-main-column-order`, {
      defaultValue: [COLUMN_METADATA_ID],
    });

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(`${type}-${COLUMNS_WIDTH_KEY_SUFFIX}`, {
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

  const dynamicSpanScoresColumns = useMemo(() => {
    if (type !== TRACE_DATA_TYPE.traces) return [];
    return (spanFeedbackScoresData?.scores?.slice() ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${COLUMN_SPAN_FEEDBACK_SCORES_ID}.${c.name}`,
        label: formatSpanScoreLabel(c.name),
        columnType: COLUMN_TYPE.number,
      }));
  }, [spanFeedbackScoresData?.scores, type]);

  const dynamicMetadataColumns = useMemo(() => {
    const paths = metadataPaths ?? [];
    const normalizedPaths = normalizeMetadataPaths(paths);
    return buildDynamicMetadataColumns(normalizedPaths);
  }, [metadataPaths]);

  // Only include feedback scores in dynamic columns cache (auto-selects new ones)
  // Metadata columns are NOT auto-selected - users must manually choose them
  const dynamicColumnsIds = useMemo(
    () => [
      ...dynamicScoresColumns.map((c) => c.id),
      ...dynamicSpanScoresColumns.map((c) => c.id),
      // Note: metadata columns are NOT included here - they won't be auto-selected
    ],
    [dynamicScoresColumns, dynamicSpanScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: `${type}-${DYNAMIC_COLUMNS_KEY_SUFFIX}`,
    dynamicColumnsIds,
    setSelectedColumns,
  });

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

    const feedbackScoresColumns = [
      userFeedbackColumn,
      ...otherDynamicColumns,
    ].map(
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

    // Add span feedback scores columns only for traces
    if (type === TRACE_DATA_TYPE.traces) {
      const spanFeedbackScoresColumns = dynamicSpanScoresColumns.map(
        ({ label, id, columnType }) => {
          // Extract the score name without the "(span)" suffix for matching
          const scoreName = parseSpanScoreName(label);
          return {
            id,
            label,
            type: columnType,
            header: FeedbackScoreHeader as never,
            cell: FeedbackScoreCell as never,
            accessorFn: (row) =>
              (row as Trace).span_feedback_scores?.find(
                (f) => f.name === scoreName,
              ),
            statisticKey: `${COLUMN_SPAN_FEEDBACK_SCORES_ID}.${scoreName}`,
            statisticDataFormater: formatScoreDisplay,
          } as ColumnData<BaseTraceData>;
        },
      );
      return [...feedbackScoresColumns, ...spanFeedbackScoresColumns];
    }

    return feedbackScoresColumns;
  }, [dynamicScoresColumns, dynamicSpanScoresColumns, type]);

  // Metadata main column (single "Metadata" column)
  const metadataMainColumnData = useMemo(() => {
    return [
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
    ] as ColumnData<BaseTraceData>[];
  }, []);

  const metadataColumnsData = useMemo(() => {
    // Add individual metadata field columns (without main "Metadata" column)
    const fieldColumns = dynamicMetadataColumns.map(({ label, id }) => {
      // Change label from ".ITEM" to "Metadata.ITEM"
      const columnLabel = label.startsWith(".")
        ? `Metadata${label}`
        : `Metadata.${label}`;

      return {
        id,
        label: columnLabel,
        type: COLUMN_TYPE.string,
        sortable: false, // Disable sorting for metadata columns - backend may not fully support it yet
        accessorFn: (row) => {
          // Use lodash/get to extract nested value
          // This will return undefined if the path doesn't exist (e.g.,
          // LLM span doesn't have metadata.tool_name)
          const value = get(row, id);

          // Handle missing values - show "-" if field doesn't exist
          // This happens when viewing spans of different types
          if (value === undefined || value === null) {
            return "-";
          }

          // Return raw value - AutodetectCell will handle type detection
          // and display primitives as text, objects/arrays as JSON
          return value;
        },
        cell: AutodetectCell as never,
      };
    }) as ColumnData<BaseTraceData>[];

    return fieldColumns;
  }, [dynamicMetadataColumns]);

  const selectedRows: Array<Trace | Span> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const getDataForExport = useCallback(async (): Promise<
    Array<Trace | Span>
  > => {
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
    (row?: Trace | Span, lastSection?: DetailsActionSectionValue) => {
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
        handleRowClick(row, DetailsActionSection.Comments);
      },
      enableUserFeedbackEditing: true,
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
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        sortable: true,
      },
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
              id: "llm_span_count",
              label: "LLM calls count",
              type: COLUMN_TYPE.number,
              accessorFn: (row: BaseTraceData) =>
                get(row, "llm_span_count", "-"),
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
          ]
        : []),
      ...(type === TRACE_DATA_TYPE.spans
        ? [
            {
              id: "type",
              label: "Type",
              type: COLUMN_TYPE.category,
              cell: SpanTypeCell as never,
            },
          ]
        : []),
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
      ...(isGuardrailsEnabled
        ? [
            {
              id: COLUMN_GUARDRAILS_ID,
              label: "Guardrails",
              statisticKey: COLUMN_GUARDRAIL_STATISTIC_ID,
              type: COLUMN_TYPE.category,
              iconType: "guardrails" as HeaderIconType,
              accessorFn: (row: BaseTraceData) =>
                row.guardrails_validations || [],
              cell: GuardrailsCell as never,
              statisticDataFormater: (value: number) => `${value} failed`,
            },
          ]
        : []),
      // Note: metadataColumnsData is NOT added here - it goes in columnSections instead
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
          ]
        : []),
      ...(type === TRACE_DATA_TYPE.spans
        ? [
            {
              id: "type",
              label: "Type",
              type: COLUMN_TYPE.category,
            },
            {
              id: "trace_id",
              label: "Trace ID",
              type: COLUMN_TYPE.string,
            },
          ]
        : []),
      {
        id: "error_info",
        label: "Errors",
        type: COLUMN_TYPE.errors,
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
      ...(type === TRACE_DATA_TYPE.traces
        ? [
            {
              id: COLUMN_SPAN_FEEDBACK_SCORES_ID,
              label: "Span feedback scores",
              type: COLUMN_TYPE.numberDictionary,
            },
          ]
        : []),
      {
        id: COLUMN_CUSTOM_ID,
        label: "Custom filter",
        type: COLUMN_TYPE.dictionary,
      },
      ...(isGuardrailsEnabled
        ? [
            {
              id: COLUMN_GUARDRAILS_ID,
              label: "Guardrails",
              type: COLUMN_TYPE.category,
            },
          ]
        : []),
    ];
  }, [type, isGuardrailsEnabled]);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<Trace | Span>(),
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
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(
        metadataMainColumnData,
        {
          columnsOrder: metadataMainColumnOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
      ...convertColumnDataToColumn<BaseTraceData, Span | Trace>(
        metadataColumnsData,
        {
          columnsOrder: metadataColumnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
    ];
  }, [
    sortableBy,
    columnData,
    columnsOrder,
    selectedColumns,
    scoresColumnsData,
    scoresColumnsOrder,
    metadataMainColumnData,
    metadataMainColumnOrder,
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

  const handleMetadataOrderChange = useCallback(
    (newOrder: string[]) => {
      const mainIds = metadataMainColumnData.map((col) => col.id);
      const fieldIds = metadataColumnsData.map((col) => col.id);

      setMetadataMainColumnOrder(newOrder.filter((id) => mainIds.includes(id)));
      setMetadataColumnsOrder(newOrder.filter((id) => fieldIds.includes(id)));
    },
    [
      metadataMainColumnData,
      metadataColumnsData,
      setMetadataMainColumnOrder,
      setMetadataColumnsOrder,
    ],
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
      ...metadataMainColumnData,
      ...metadataColumnsData,
    ];
    const allMetadataOrder = [
      ...metadataMainColumnOrder,
      ...metadataColumnsOrder,
    ];

    if (allMetadataColumns.length > 0) {
      sections.push({
        title: "Metadata",
        columns: allMetadataColumns,
        order: allMetadataOrder,
        onOrderChange: handleMetadataOrderChange,
      });
    }

    return sections;
  }, [
    scoresColumnsData,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    metadataMainColumnData,
    metadataMainColumnOrder,
    metadataColumnsData,
    metadataColumnsOrder,
    handleMetadataOrderChange,
  ]);

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
            columns={filtersColumnData}
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
            type={type as TRACE_DATA_TYPE}
          />
          <Separator orientation="vertical" className="mx-2 h-4" />
          <MetricDateRangeSelect
            value={dateRange}
            onChangeValue={handleDateRangeChange}
            minDate={minDate}
            maxDate={maxDate}
          />
          <TooltipWrapper
            content={`Refresh ${
              type === TRACE_DATA_TYPE.traces ? "traces" : "spans"
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
            excludeFromSelectAll={
              metadataColumnsData.length > 0
                ? metadataColumnsData.map((col) => col.id)
                : []
            }
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>

      <DataTableStateHandler
        isLoading={isTableLoading}
        isEmpty={showEmptyState}
        emptyState={<NoTracesPage type={type} />}
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
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        open={Boolean(traceId) && !threadId}
        onClose={handleClose}
        onRowChange={handleRowChange}
      />
      <ThreadDetailsPanel
        projectId={projectId}
        projectName={projectName}
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
