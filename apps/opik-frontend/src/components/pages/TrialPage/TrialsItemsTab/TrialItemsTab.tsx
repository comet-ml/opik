import React, { useCallback, useEffect, useMemo, useState } from "react";
import get from "lodash/get";
import sortBy from "lodash/sortBy";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import { ColumnPinningState, createColumnHelper } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

import {
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  ROW_HEIGHT,
} from "@/types/shared";
import { EXPERIMENT_ITEM_OUTPUT_PREFIX } from "@/constants/experiments";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import TrialPassedCell from "./TrialPassedCell";
import TrialScoreCell from "./TrialScoreCell";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import ExplainerCallout from "@/components/shared/ExplainerCallout/ExplainerCallout";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import {
  Experiment,
  ExperimentItem,
  ExecutionPolicy,
  ExperimentsCompare,
} from "@/types/datasets";
import { useTruncationEnabled } from "@/components/server-sync-provider";
import { convertColumnDataToColumn, hasAnyVisibleColumns } from "@/lib/table";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import useCompareExperimentsColumns from "@/api/datasets/useCompareExperimentsColumns";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import ExperimentsFeedbackScoresSelect from "@/components/pages-shared/experiments/ExperimentsFeedbackScoresSelect/ExperimentsFeedbackScoresSelect";
import { calculateHeightStyle } from "@/components/shared/DataTable/utils";
import SectionHeader from "@/components/shared/DataTableHeaders/SectionHeader";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import { EXPLAINER_ID, EXPLAINERS_MAP } from "@/constants/explainers";
import { generateDistinctColorMap } from "@/components/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";

type PassFilterValue = "all" | "passed" | "failed";

type FlattenedTrialItem = {
  id: string;
  dataset_item_id: string;
  data: object;
  experimentId: string;
  experimentItem: ExperimentItem;
  allRuns: ExperimentItem[];
  executionPolicy?: ExecutionPolicy;
  runSummary?: { passed_runs: number; total_runs: number; status: string };
};

const getRowId = (d: FlattenedTrialItem) => d.id;

const columnHelper = createColumnHelper<FlattenedTrialItem>();

const REFETCH_INTERVAL = 30000;

const SELECTED_COLUMNS_KEY = "compare-trials-selected-columns-v5";
const COLUMNS_WIDTH_KEY = "compare-trials-columns-width";
const COLUMNS_ORDER_KEY = "compare-trials-columns-order";
const DYNAMIC_COLUMNS_KEY = "compare-trials-dynamic-columns-v2";
const COLUMNS_SCORES_ORDER_KEY = "compare-trials-scores-columns-order";
const COLUMNS_OUTPUT_ORDER_KEY = "compare-trials-output-columns-order";
const PAGINATION_SIZE_KEY = "compare-trials-pagination-size";
const ROW_HEIGHT_KEY = "compare-trials-row-height";

export const FILTER_COLUMNS: ColumnData<FlattenedTrialItem>[] = [
  {
    id: "output",
    label: "Evaluation task",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Optimizations scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = [];

const DEFAULT_COLUMNS: ColumnData<FlattenedTrialItem>[] = [
  {
    id: COLUMN_ID_ID,
    label: "Evaluation suite item ID",
    type: COLUMN_TYPE.string,
    accessorFn: (row) => row.dataset_item_id,
    cell: IdCell as never,
    size: 165,
  },
];

export type TrialItemsTabProps = {
  objectiveName?: string;
  datasetId: string;
  experimentsIds: string[];
  experiments?: Experiment[];
  isEvaluationSuite?: boolean;
};

const TrialItemsTab: React.FC<TrialItemsTabProps> = ({
  objectiveName,
  datasetId,
  experimentsIds = [],
  experiments,
  isEvaluationSuite = false,
}) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);
  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
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

  const [height, setHeight] = useQueryParamAndLocalStorageState<
    string | null | undefined
  >({
    localStorageKey: ROW_HEIGHT_KEY,
    queryKey: "height",
    defaultValue: ROW_HEIGHT.small,
    queryParamConfig: StringParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: ExperimentsFeedbackScoresSelect,
          keyComponentProps: {
            experimentsIds,
            placeholder: "Select score",
          },
        },
      },
    }),
    [experimentsIds],
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

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

  const [scoresColumnsOrder, setScoresColumnsOrder] = useLocalStorageState<
    string[]
  >(COLUMNS_SCORES_ORDER_KEY, {
    defaultValue: [],
  });

  const [outputColumnsOrder, setOutputColumnsOrder] = useLocalStorageState<
    string[]
  >(COLUMNS_OUTPUT_ORDER_KEY, {
    defaultValue: [],
  });

  const truncationEnabled = useTruncationEnabled();

  const { data, isPending, isPlaceholderData, isFetching } =
    useCompareExperimentsList(
      {
        workspaceName,
        datasetId,
        experimentsIds,
        filters,
        truncate: truncationEnabled,
        page: page as number,
        size: size as number,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const { data: experimentsOutputData, isPending: isExperimentsOutputPending } =
    useCompareExperimentsColumns(
      {
        datasetId,
        experimentsIds,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const apiTotal = data?.total ?? 0;
  const noDataText = "There is no data for the selected trials";

  const allFlatRows = useMemo(() => {
    const apiRows: ExperimentsCompare[] = data?.content ?? [];
    const flat: FlattenedTrialItem[] = [];

    for (const row of apiRows) {
      const itemsByExperiment = new Map<string, ExperimentItem[]>();
      for (const item of row.experiment_items ?? []) {
        const existing = itemsByExperiment.get(item.experiment_id) ?? [];
        existing.push(item);
        itemsByExperiment.set(item.experiment_id, existing);
      }

      for (const [experimentId, runs] of itemsByExperiment) {
        flat.push({
          id: `${row.id}_${experimentId}`,
          dataset_item_id: row.id,
          data: row.data,
          experimentId,
          experimentItem: runs[0],
          allRuns: runs,
          executionPolicy: row.execution_policy,
          runSummary: row.run_summaries_by_experiment?.[experimentId],
        });
      }
    }

    return flat;
  }, [data?.content]);

  const [passFilter, setPassFilter] = useState<PassFilterValue>("all");

  const { rows, passedCount, failedCount } = useMemo(() => {
    if (!isEvaluationSuite) {
      return { rows: allFlatRows, passedCount: 0, failedCount: 0 };
    }

    const getItemStatus = (row: FlattenedTrialItem): boolean | undefined => {
      if (row.runSummary) {
        return row.runSummary.status === "passed";
      }
      const firstRun = row.experimentItem;
      if (firstRun.status) {
        return firstRun.status === "passed";
      }
      return undefined;
    };

    let passed = 0;
    let failed = 0;

    allFlatRows.forEach((row) => {
      const itemPassed = getItemStatus(row);
      if (itemPassed === undefined) return;
      if (itemPassed) {
        passed++;
      } else {
        failed++;
      }
    });

    if (passFilter === "all") {
      return { rows: allFlatRows, passedCount: passed, failedCount: failed };
    }

    const filtered = allFlatRows.filter((row) => {
      const itemPassed = getItemStatus(row);
      if (itemPassed === undefined) return false;
      return passFilter === "passed" ? itemPassed : !itemPassed;
    });

    return { rows: filtered, passedCount: passed, failedCount: failed };
  }, [allFlatRows, passFilter, isEvaluationSuite]);

  const dynamicDatasetColumns = useMemo(() => {
    return (data?.columns ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: c.name,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      }));
  }, [data]);

  const dynamicOutputColumns = useMemo(() => {
    return (experimentsOutputData?.columns ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `${EXPERIMENT_ITEM_OUTPUT_PREFIX}.${c.name}`,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      }));
  }, [experimentsOutputData]);

  const dynamicColumnsIds = useMemo(
    () => [
      ...dynamicDatasetColumns.map((c) => c.id),
      ...dynamicOutputColumns.map((c) => c.id),
    ],
    [dynamicDatasetColumns, dynamicOutputColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const datasetColumnsData = useMemo(() => {
    return [
      ...dynamicDatasetColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row: FlattenedTrialItem) =>
              get(row, ["data", label], ""),
            cell: AutodetectCell as never,
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<FlattenedTrialItem>,
      ),
    ];
  }, [dynamicDatasetColumns]);

  const outputColumnsData = useMemo(() => {
    return [
      ...dynamicOutputColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row: FlattenedTrialItem) =>
              get(row.experimentItem.output, label, ""),
            cell: AutodetectCell as never,
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<FlattenedTrialItem>,
      ),
    ];
  }, [dynamicOutputColumns]);

  const scoresColumnsData = useMemo(() => {
    if (isEvaluationSuite) {
      return [
        {
          id: "score_passed",
          label: "passed",
          type: COLUMN_TYPE.string,
          cell: TrialPassedCell as never,
          customMeta: {
            experimentsIds,
          },
        },
      ] as ColumnData<FlattenedTrialItem>[];
    }

    const feedbackScoreNames = new Set<string>();

    experiments?.forEach((experiment) => {
      experiment.feedback_scores?.forEach((score) => {
        feedbackScoreNames.add(score.name);
      });
      experiment.experiment_scores?.forEach((score) => {
        feedbackScoreNames.add(score.name);
      });
    });

    const sortedScoreNames = Array.from(feedbackScoreNames).sort((a, b) => {
      if (a === objectiveName) return -1;
      if (b === objectiveName) return 1;
      return a.localeCompare(b, undefined, { sensitivity: "base" });
    });

    const colorMap =
      objectiveName && sortedScoreNames.length > 0
        ? generateDistinctColorMap(
            objectiveName,
            sortedScoreNames.filter((name) => name !== objectiveName),
          )
        : undefined;

    return sortedScoreNames.map((scoreName) => ({
      id: `score_${scoreName}`,
      label: scoreName,
      type: COLUMN_TYPE.number,
      header: FeedbackScoreHeader as never,
      cell: TrialScoreCell as never,
      accessorFn: (row: FlattenedTrialItem) => {
        return row.experimentItem.feedback_scores?.find(
          (s) => s.name === scoreName,
        );
      },
      customMeta: {
        scoreName,
        colorMap,
      },
    })) as ColumnData<FlattenedTrialItem>[];
  }, [experiments, experimentsIds, objectiveName, isEvaluationSuite]);

  useEffect(() => {
    const scoreColumnIds = scoresColumnsData.map((col) => col.id);
    const missingScoreColumns = scoreColumnIds.filter(
      (id) => !selectedColumns.includes(id),
    );

    if (missingScoreColumns.length > 0) {
      setSelectedColumns((prev) => [...prev, ...missingScoreColumns]);
    }
  }, [scoresColumnsData, selectedColumns, setSelectedColumns]);

  const activeDefaultColumns = DEFAULT_COLUMNS;

  const columns = useMemo(() => {
    const retVal = [
      ...convertColumnDataToColumn<FlattenedTrialItem, FlattenedTrialItem>(
        activeDefaultColumns,
        {
          selectedColumns,
          columnsOrder,
        },
      ),
    ];

    if (hasAnyVisibleColumns(datasetColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "dataset",
          meta: {
            header: "Evaluation suite",
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            FlattenedTrialItem,
            FlattenedTrialItem
          >(datasetColumnsData, {
            selectedColumns,
            columnsOrder,
          }),
        }),
      );
    }

    if (hasAnyVisibleColumns(outputColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "evaluation",
          meta: {
            header: "Evaluation task",
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            FlattenedTrialItem,
            FlattenedTrialItem
          >(outputColumnsData, {
            selectedColumns,
            columnsOrder: outputColumnsOrder,
          }),
        }),
      );
    }

    if (hasAnyVisibleColumns(scoresColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "scores",
          meta: {
            header: "Optimizations scores",
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            FlattenedTrialItem,
            FlattenedTrialItem
          >(scoresColumnsData, {
            selectedColumns,
            columnsOrder: scoresColumnsOrder,
          }),
        }),
      );
    }

    return retVal;
  }, [
    activeDefaultColumns,
    datasetColumnsData,
    selectedColumns,
    outputColumnsData,
    scoresColumnsData,
    columnsOrder,
    outputColumnsOrder,
    scoresColumnsOrder,
  ]);

  const filterColumns = useMemo(() => {
    return [
      ...sortBy(dynamicDatasetColumns, "label").map(
        ({ id, label, columnType }) => ({
          id,
          label: `${label} (Evaluation suite)`,
          type: columnType,
        }),
      ),
      ...FILTER_COLUMNS,
    ];
  }, [dynamicDatasetColumns]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const getRowHeightStyle = useCallback(
    (height: ROW_HEIGHT) => calculateHeightStyle(height),
    [],
  );

  const columnSections = useMemo(() => {
    return [
      {
        title: "Evaluation task",
        columns: outputColumnsData,
        order: outputColumnsOrder,
        onOrderChange: setOutputColumnsOrder,
      },
      {
        title: "Optimizations scores",
        columns: scoresColumnsData,
        order: scoresColumnsOrder,
        onOrderChange: setScoresColumnsOrder,
      },
    ];
  }, [
    outputColumnsData,
    outputColumnsOrder,
    setOutputColumnsOrder,
    scoresColumnsData,
    scoresColumnsOrder,
    setScoresColumnsOrder,
  ]);

  if (isPending || isExperimentsOutputPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer direction="horizontal" limitWidth>
        <ExplainerCallout
          className="mb-4"
          {...EXPLAINERS_MAP[EXPLAINER_ID.what_are_trial_items]}
        />
      </PageBodyStickyContainer>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <FiltersButton
            columns={filterColumns}
            config={filtersConfig as never}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
          {isEvaluationSuite && (
            <ToggleGroup
              type="single"
              value={passFilter}
              onValueChange={(v) => {
                if (v) setPassFilter(v as PassFilterValue);
              }}
              variant="default"
              size="sm"
            >
              <ToggleGroupItem value="all">
                All ({allFlatRows.length})
              </ToggleGroupItem>
              <ToggleGroupItem value="passed">
                Passed ({passedCount})
              </ToggleGroupItem>
              <ToggleGroupItem value="failed">
                Failed ({failedCount})
              </ToggleGroupItem>
            </ToggleGroup>
          )}
        </div>
        <div className="flex items-center gap-2">
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={[...activeDefaultColumns, ...datasetColumnsData]}
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
        data={rows}
        resizeConfig={resizeConfig}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        getRowHeightStyle={getRowHeightStyle}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
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
          total={
            passFilter !== "all" ? rows.length : Math.max(apiTotal, rows.length)
          }
          supportsTruncation
          truncationEnabled={truncationEnabled}
        />
      </PageBodyStickyContainer>
      <TraceDetailsPanel
        traceId={traceId!}
        spanId={spanId!}
        setSpanId={setSpanId}
        open={Boolean(traceId)}
        onClose={() => {
          setTraceId("");
          setSpanId("");
        }}
      />
    </>
  );
};

export default TrialItemsTab;
