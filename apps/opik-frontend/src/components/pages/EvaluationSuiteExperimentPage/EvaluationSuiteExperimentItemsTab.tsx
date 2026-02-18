import React, { useMemo } from "react";
import isUndefined from "lodash/isUndefined";
import get from "lodash/get";
import sortBy from "lodash/sortBy";
import { ColumnPinningState, createColumnHelper } from "@tanstack/react-table";

import {
  COLUMN_COMMENTS_ID,
  COLUMN_DURATION_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  COLUMN_USAGE_ID,
  ColumnData,
  DynamicColumn,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import CompareExperimentsOutputCell from "@/components/pages-shared/experiments/CompareExperimentsOutputCell/CompareExperimentsOutputCell";
import CompareExperimentsFeedbackScoreCell from "@/components/pages-shared/experiments/CompareExperimentsFeedbackScoreCell/CompareExperimentsFeedbackScoreCell";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import EvaluationSuiteExperimentPanel from "./ExperimentItemSidebar/EvaluationSuiteExperimentPanel";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import {
  DATASET_ITEM_SOURCE,
  ExperimentItem,
  ExperimentsCompare,
} from "@/types/datasets";
import {
  ExperimentItemStatus,
  BehaviorResult,
} from "@/types/evaluation-suites";
import { useTruncationEnabled } from "@/components/server-sync-provider";
import {
  convertColumnDataToColumn,
  hasAnyVisibleColumns,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import { Separator } from "@/components/ui/separator";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import ExperimentsFeedbackScoresSelect from "@/components/pages-shared/experiments/ExperimentsFeedbackScoresSelect/ExperimentsFeedbackScoresSelect";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import { formatDuration } from "@/lib/date";
import { formatCost } from "@/lib/money";
import SectionHeader from "@/components/shared/DataTableHeaders/SectionHeader";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import DurationCell from "@/components/shared/DataTableCells/DurationCell";
import CostCell from "@/components/shared/DataTableCells/CostCell";
import {
  USER_FEEDBACK_COLUMN_ID,
  USER_FEEDBACK_NAME,
} from "@/constants/shared";
import useExperimentItemsState from "@/components/pages-shared/experiments/useExperimentItemsState";
import useExperimentItemsData from "@/components/pages-shared/experiments/useExperimentItemsData";
import useExperimentItemsSidebar from "@/components/pages-shared/experiments/useExperimentItemsSidebar";
import PassedCell from "./PassedCell";

const getRowId = (d: ExperimentsCompare) => d.id;

const columnHelper = createColumnHelper<ExperimentsCompare>();

const STORAGE_PREFIX = "eval-suite-experiment";
const DYNAMIC_COLUMNS_KEY = "eval-suite-experiment-dynamic-columns";
const COLUMN_PASSED_ID = "passed";

// TODO: Remove mock rows once real API data is available
const MOCK_ROWS: ExperimentsCompare[] = [
  // Row 1 — Passed (single run): green "Yes" in table, PASSED badge in sidebar
  {
    id: "mock-passed-single-run",
    data: { question: "What is 2+2?", expected_answer: "4" },
    source: DATASET_ITEM_SOURCE.manual,
    created_at: "2025-01-01T00:00:00Z",
    last_updated_at: "2025-01-01T00:00:00Z",
    experiment_items: [
      {
        id: "mock-item-1-run-1",
        experiment_id: "mock-experiment",
        dataset_item_id: "mock-passed-single-run",
        input: { question: "What is 2+2?" },
        output: { answer: "4" },
        created_at: "2025-01-01T00:00:00Z",
        last_updated_at: "2025-01-01T00:00:00Z",
        status: ExperimentItemStatus.PASSED,
        behavior_results: [
          { behavior_name: "Correct answer", passed: true },
          { behavior_name: "Concise response", passed: true },
          { behavior_name: "No hallucination", passed: true },
        ] as BehaviorResult[],
      } as unknown as ExperimentItem,
    ],
  },
  // Row 2 — Failed (multi-run, 2 runs): red "No (1/2)" in table, FAILED badge + Run tabs in sidebar
  {
    id: "mock-failed-multi-run",
    data: {
      question: "Explain quantum entanglement",
      expected_answer: "A phenomenon where particles are correlated",
    },
    source: DATASET_ITEM_SOURCE.manual,
    created_at: "2025-01-01T00:00:01Z",
    last_updated_at: "2025-01-01T00:00:01Z",
    experiment_items: [
      // Run 1: failed — 2 pass, 1 fail with reason
      {
        id: "mock-item-2-run-1",
        experiment_id: "mock-experiment",
        dataset_item_id: "mock-failed-multi-run",
        input: { question: "Explain quantum entanglement" },
        output: {
          answer:
            "Quantum entanglement is when particles share a mystical bond",
        },
        created_at: "2025-01-01T00:00:01Z",
        last_updated_at: "2025-01-01T00:00:01Z",
        status: ExperimentItemStatus.FAILED,
        behavior_results: [
          { behavior_name: "Scientifically accurate", passed: true },
          { behavior_name: "Mentions correlation", passed: false, reason: "Response uses 'mystical bond' instead of describing particle correlation" },
          { behavior_name: "No hallucination", passed: true },
        ] as BehaviorResult[],
      } as unknown as ExperimentItem,
      // Run 2: passed — all 3 behaviors pass
      {
        id: "mock-item-2-run-2",
        experiment_id: "mock-experiment",
        dataset_item_id: "mock-failed-multi-run",
        input: { question: "Explain quantum entanglement" },
        output: {
          answer:
            "Quantum entanglement is a phenomenon where two particles become correlated such that the state of one instantly influences the other.",
        },
        created_at: "2025-01-01T00:00:02Z",
        last_updated_at: "2025-01-01T00:00:02Z",
        status: ExperimentItemStatus.PASSED,
        behavior_results: [
          { behavior_name: "Scientifically accurate", passed: true },
          { behavior_name: "Mentions correlation", passed: true },
          { behavior_name: "No hallucination", passed: true },
        ] as BehaviorResult[],
      } as unknown as ExperimentItem,
    ],
  },
];

const FILTER_COLUMNS: ColumnData<ExperimentsCompare>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID (Dataset item)",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_DURATION_ID,
    label: "Duration",
    type: COLUMN_TYPE.duration,
  },
  {
    id: COLUMN_COMMENTS_ID,
    label: "Comments",
    type: COLUMN_TYPE.string,
  },
  {
    id: "output",
    label: "Evaluation task",
    type: COLUMN_TYPE.string,
  },
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID],
  right: [COLUMN_PASSED_ID],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_ID_ID,
  COLUMN_COMMENTS_ID,
  USER_FEEDBACK_COLUMN_ID,
];

type EvaluationSuiteExperimentItemsTabProps = {
  workspaceName: string;
  suiteId: string;
  experimentId: string;
};

const EvaluationSuiteExperimentItemsTab: React.FunctionComponent<
  EvaluationSuiteExperimentItemsTabProps
> = ({ workspaceName, suiteId, experimentId }) => {
  const truncationEnabled = useTruncationEnabled();
  const experimentsIds = useMemo(() => [experimentId], [experimentId]);

  const {
    page,
    setPage,
    size,
    setSize,
    height,
    setHeight,
    search,
    setSearch,
    filters,
    setFilters,
    columnsWidth,
    setColumnsWidth,
    selectedColumns,
    setSelectedColumns,
    columnsOrder,
    setColumnsOrder,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    outputColumnsOrder,
    setOutputColumnsOrder,
    sorting,
    setSorting,
    rowSelection,
    setRowSelection,
  } = useExperimentItemsState({
    storagePrefix: STORAGE_PREFIX,
    defaultSelectedColumns: DEFAULT_SELECTED_COLUMNS,
  });

  const {
    rows,
    total,
    sortableColumns,
    columnsStatistic,
    dynamicDatasetColumns,
    dynamicOutputColumns,
    dynamicScoresColumns,
    isPending,
    isFetching,
    isPlaceholderData,
  } = useExperimentItemsData({
    workspaceName,
    datasetId: suiteId,
    experimentsIds,
    filters,
    sorting,
    search: search as string,
    page: page as number,
    size: size as number,
    truncate: truncationEnabled,
    setSelectedColumns,
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
  });

  const mergedRows = useMemo(
    () => [...MOCK_ROWS, ...rows],
    [rows],
  );

  const {
    activeRowId,
    activeRow,
    traceId,
    setTraceId,
    spanId,
    setSpanId,
    handleRowClick,
    handleRowChange,
    handleClose,
    hasNext,
    hasPrevious,
    setExpandedCommentSections,
  } = useExperimentItemsSidebar(mergedRows);

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

  const datasetColumnsData = useMemo(() => {
    return dynamicDatasetColumns.map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          accessorFn: (row) => get(row, ["data", label], ""),
          cell: AutodetectCell as never,
          ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
        }) as ColumnData<ExperimentsCompare>,
    );
  }, [dynamicDatasetColumns]);

  const outputColumnsData = useMemo(() => {
    return [
      ...dynamicOutputColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            cell: CompareExperimentsOutputCell as never,
            customMeta: {
              experimentsIds,
              outputKey: label,
              openTrace: setTraceId,
            },
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<ExperimentsCompare>,
      ),
      {
        id: COLUMN_DURATION_ID,
        label: "Duration",
        type: COLUMN_TYPE.duration,
        cell: DurationCell as never,
        statisticKey: "duration",
        statisticDataFormater: formatDuration,
        statisticTooltipFormater: formatDuration,
        customMeta: {
          experimentsIds,
        },
      },
      {
        id: `${COLUMN_USAGE_ID}.total_tokens`,
        label: "Total tokens",
        type: COLUMN_TYPE.number,
        cell: CostCell as never,
        statisticKey: `${COLUMN_USAGE_ID}.total_tokens`,
        supportsPercentiles: true,
        customMeta: {
          experimentsIds,
          accessor: `${COLUMN_USAGE_ID}.total_tokens`,
          formatter: (value: number) => value.toLocaleString(),
        },
      },
      {
        id: "total_estimated_cost",
        label: "Estimated cost",
        type: COLUMN_TYPE.cost,
        cell: CostCell as never,
        statisticKey: "total_estimated_cost",
        statisticDataFormater: formatCost,
        statisticTooltipFormater: (value: number) =>
          formatCost(value, { modifier: "full" }),
        supportsPercentiles: true,
        customMeta: {
          experimentsIds,
          accessor: "total_estimated_cost",
        },
      },
      {
        id: COLUMN_COMMENTS_ID,
        label: "Comments",
        type: COLUMN_TYPE.string,
        cell: CommentsCell as never,
        sortable: isColumnSortable(COLUMN_COMMENTS_ID, sortableColumns),
        customMeta: {
          experimentsIds,
        },
      } as ColumnData<ExperimentsCompare>,
    ];
  }, [dynamicOutputColumns, experimentsIds, setTraceId, sortableColumns]);

  const scoresColumnsData = useMemo(() => {
    const userFeedbackColumn: DynamicColumn = {
      id: USER_FEEDBACK_COLUMN_ID,
      label: USER_FEEDBACK_NAME,
      columnType: COLUMN_TYPE.number,
    };

    const otherDynamicColumns = dynamicScoresColumns.filter(
      (col) => col.id !== USER_FEEDBACK_COLUMN_ID,
    );

    return [userFeedbackColumn, ...otherDynamicColumns].map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          header: FeedbackScoreHeader as never,
          cell: CompareExperimentsFeedbackScoreCell as never,
          statisticKey: `${COLUMN_FEEDBACK_SCORES_ID}.${label}`,
          statisticDataFormater: formatScoreDisplay,
          supportsPercentiles: true,
          customMeta: {
            experimentsIds,
            scoreName: label,
          },
        }) as ColumnData<ExperimentsCompare>,
    );
  }, [dynamicScoresColumns, experimentsIds]);

  const columns = useMemo(() => {
    const retVal = [
      generateSelectColumDef<ExperimentsCompare>({}),
      mapColumnDataFields<ExperimentsCompare, ExperimentsCompare>({
        id: COLUMN_ID_ID,
        label: "ID (Dataset item)",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        size: 180,
        sortable: isColumnSortable(COLUMN_ID_ID, sortableColumns),
      }),
    ];

    if (hasAnyVisibleColumns(datasetColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "dataset",
          meta: {
            header: "Dataset",
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(datasetColumnsData, {
            selectedColumns,
            columnsOrder,
            sortableColumns,
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
            ExperimentsCompare,
            ExperimentsCompare
          >(outputColumnsData, {
            selectedColumns,
            columnsOrder: outputColumnsOrder,
            sortableColumns,
          }),
        }),
      );
    }

    if (hasAnyVisibleColumns(scoresColumnsData, selectedColumns)) {
      retVal.push(
        columnHelper.group({
          id: "scores",
          meta: {
            header: "Feedback scores",
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(scoresColumnsData, {
            selectedColumns,
            columnsOrder: scoresColumnsOrder,
            sortableColumns,
          }),
        }),
      );
    }

    retVal.push(
      mapColumnDataFields<ExperimentsCompare, ExperimentsCompare>({
        id: COLUMN_PASSED_ID,
        label: "Passed",
        type: COLUMN_TYPE.string,
        cell: PassedCell as never,
        size: 100,
      }),
    );

    return retVal;
  }, [
    datasetColumnsData,
    selectedColumns,
    outputColumnsData,
    scoresColumnsData,
    columnsOrder,
    outputColumnsOrder,
    scoresColumnsOrder,
    sortableColumns,
  ]);

  const filterColumns = useMemo(() => {
    return [
      ...sortBy(dynamicDatasetColumns, "label").map(
        ({ id, label, columnType }) => ({
          id,
          label: `${label} (Dataset)`,
          type: columnType,
        }),
      ),
      ...sortBy(dynamicOutputColumns, "label").map(({ id, label }) => ({
        id,
        label: `${label} (Output)`,
        type: COLUMN_TYPE.string,
      })),
      ...FILTER_COLUMNS,
    ];
  }, [dynamicDatasetColumns, dynamicOutputColumns]);

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
        title: "Evaluation task",
        columns: outputColumnsData,
        order: outputColumnsOrder,
        onOrderChange: setOutputColumnsOrder,
      },
      {
        title: "Feedback scores",
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

  const meta = useMemo(
    () => ({
      onCommentsReply: (row: ExperimentsCompare, idx?: number) => {
        handleRowClick(row);

        if (isUndefined(idx)) return;

        setExpandedCommentSections([String(idx)]);
      },
      columnsStatistic,
      enableUserFeedbackEditing: true,
    }),
    [handleRowClick, setExpandedCommentSections, columnsStatistic],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer
        className="flex flex-wrap items-center justify-between gap-x-8 gap-y-2 pb-6 pt-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder="Search dataset items"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={filterColumns}
            config={filtersConfig as never}
            filters={filters}
            onChange={setFilters}
            layout="icon"
          />
        </div>
        <div className="flex items-center gap-2">
          <Separator orientation="vertical" className="mx-2 h-4" />
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={datasetColumnsData}
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
        data={mergedRows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        sortConfig={{
          enabled: true,
          sorting,
          setSorting,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title="There are no experiment items yet" />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
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
          total={total}
          supportsTruncation
          truncationEnabled={truncationEnabled}
        />
      </PageBodyStickyContainer>
      <EvaluationSuiteExperimentPanel
        experimentsCompareId={activeRowId}
        experimentsCompare={activeRow}
        experimentsIds={experimentsIds}
        datasetId={suiteId}
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        openTrace={setTraceId as OnChangeFn<string>}
        onClose={handleClose}
        onRowChange={handleRowChange}
        isTraceDetailsOpened={Boolean(traceId)}
      />
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

export default EvaluationSuiteExperimentItemsTab;
