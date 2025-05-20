import React, { useCallback, useMemo, useState } from "react";
import isUndefined from "lodash/isUndefined";
import findIndex from "lodash/findIndex";
import find from "lodash/find";
import get from "lodash/get";
import sortBy from "lodash/sortBy";
import {
  ArrayParam,
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import {
  ColumnDef,
  ColumnPinningState,
  createColumnHelper,
  RowSelectionState,
} from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_COMMENTS_ID,
  COLUMN_CREATED_AT_ID,
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import { EXPERIMENT_ITEM_OUTPUT_PREFIX } from "@/constants/experiments";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableVirtualBody from "@/components/shared/DataTable/DataTableVirtualBody";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import CompareExperimentsOutputCell from "@/components/pages-shared/experiments/CompareExperimentsOutputCell/CompareExperimentsOutputCell";
import CompareExperimentsFeedbackScoreCell from "@/components/pages-shared/experiments/CompareExperimentsFeedbackScoreCell/CompareExperimentsFeedbackScoreCell";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import CompareExperimentsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsPanel";
import CompareExperimentsActionsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import CompareExperimentsNameCell from "@/components/pages-shared/experiments/CompareExperimentsNameCell/CompareExperimentsNameCell";
import CompareExperimentsNameHeader from "@/components/pages-shared/experiments/CompareExperimentsNameHeader/CompareExperimentsNameHeader";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import { formatDate } from "@/lib/date";
import {
  convertColumnDataToColumn,
  hasAnyVisibleColumns,
  mapColumnDataFields,
} from "@/lib/table";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import { Separator } from "@/components/ui/separator";
import useExperimentsFeedbackScoresNames from "@/api/datasets/useExperimentsFeedbackScoresNames";
import useCompareExperimentsColumns from "@/api/datasets/useCompareExperimentsColumns";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import ExperimentsFeedbackScoresSelect from "@/components/pages-shared/experiments/ExperimentsFeedbackScoresSelect/ExperimentsFeedbackScoresSelect";
import {
  calculateHeightStyle,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { calculateLineHeight } from "@/lib/experiments";
import SectionHeader from "@/components/shared/DataTableHeaders/SectionHeader";
import CommentsCell from "@/components/shared/DataTableCells/CommentsCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

const getRowId = (d: ExperimentsCompare) => d.id;

const calculateVerticalAlignment = (count: number) =>
  count === 1 ? undefined : CELL_VERTICAL_ALIGNMENT.start;

const columnHelper = createColumnHelper<ExperimentsCompare>();

const REFETCH_INTERVAL = 30000;
const COLUMN_EXPERIMENT_NAME_ID = "experiment_name";

const SELECTED_COLUMNS_KEY = "compare-experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "compare-experiments-columns-width";
const COLUMNS_ORDER_KEY = "compare-experiments-columns-order";
const DYNAMIC_COLUMNS_KEY = "compare-experiments-dynamic-columns";
const COLUMNS_SCORES_ORDER_KEY = "compare-experiments-scores-columns-order";
const COLUMNS_OUTPUT_ORDER_KEY = "compare-experiments-output-columns-order";
const PAGINATION_SIZE_KEY = "compare-experiments-pagination-size";
const ROW_HEIGHT_KEY = "compare-experiments-row-height";

export const FILTER_COLUMNS: ColumnData<ExperimentsCompare>[] = [
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

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = ["id", COLUMN_COMMENTS_ID];

export type ExperimentItemsTabProps = {
  experimentsIds: string[];
  experiments?: Experiment[];
};

const ExperimentItemsTab: React.FunctionComponent<ExperimentItemsTabProps> = ({
  experimentsIds = [],
  experiments,
}) => {
  const datasetId = useDatasetIdFromCompareExperimentsURL();
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [activeRowId = "", setActiveRowId] = useQueryParam("row", StringParam, {
    updateType: "replaceIn",
  });

  const [, setExpandedCommentSections] = useQueryParam(
    "expandedCommentSections",
    ArrayParam,
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

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds,
      filters,
      truncate: true,
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

  const { data: feedbackScoresData, isPending: isFeedbackScoresPending } =
    useExperimentsFeedbackScoresNames(
      {
        experimentsIds,
      },
      {
        placeholderData: keepPreviousData,
        refetchInterval: REFETCH_INTERVAL,
      },
    );

  const experimentsCount = experimentsIds.length;
  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = "There is no data for the selected experiments";

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
    () => [
      ...dynamicDatasetColumns.map((c) => c.id),
      ...dynamicOutputColumns.map((c) => c.id),
      ...dynamicScoresColumns.map((c) => c.id),
      COLUMN_COMMENTS_ID,
    ],
    [dynamicDatasetColumns, dynamicOutputColumns, dynamicScoresColumns],
  );

  useDynamicColumnsCache({
    dynamicColumnsKey: DYNAMIC_COLUMNS_KEY,
    dynamicColumnsIds,
    setSelectedColumns,
  });

  const datasetColumnsData = useMemo(() => {
    return [
      {
        id: COLUMN_CREATED_AT_ID,
        label: "Created",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.created_at),
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
      },
      ...dynamicDatasetColumns.map(
        ({ label, id, columnType }) =>
          ({
            id,
            label,
            type: columnType,
            accessorFn: (row) => get(row, ["data", label], ""),
            cell: AutodetectCell as never,
            verticalAlignment: calculateVerticalAlignment(experimentsCount),
            overrideRowHeight:
              experimentsCount === 1 ? undefined : ROW_HEIGHT.large,
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<ExperimentsCompare>,
      ),
    ];
  }, [dynamicDatasetColumns, experimentsCount]);

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
              experiments,
              experimentsIds,
              outputKey: label,
              openTrace: setTraceId,
            },
            ...(columnType === COLUMN_TYPE.dictionary && { size: 400 }),
          }) as ColumnData<ExperimentsCompare>,
      ),
      {
        id: COLUMN_COMMENTS_ID,
        label: "Comments",
        type: COLUMN_TYPE.string,
        cell: CommentsCell.Compare as never,
        customMeta: {
          experimentsIds,
        },
      } as ColumnData<ExperimentsCompare>,
    ];
  }, [dynamicOutputColumns, experiments, experimentsIds, setTraceId]);

  const scoresColumnsData = useMemo(() => {
    return dynamicScoresColumns.map(
      ({ label, id, columnType }) =>
        ({
          id,
          label,
          type: columnType,
          header: FeedbackScoreHeader as never,
          cell: CompareExperimentsFeedbackScoreCell as never,
          customMeta: {
            experimentsIds,
            feedbackKey: label,
          },
        }) as ColumnData<ExperimentsCompare>,
    );
  }, [dynamicScoresColumns, experimentsIds]);

  const selectedRows: Array<ExperimentsCompare> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleRowClick = useCallback(
    (row: ExperimentsCompare) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

  const columns = useMemo(() => {
    const retVal = [
      generateSelectColumDef<ExperimentsCompare>({
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
      }),
      mapColumnDataFields<ExperimentsCompare, ExperimentsCompare>({
        id: COLUMN_ID_ID,
        label: "ID (Dataset item)",
        type: COLUMN_TYPE.string,
        cell: LinkCell as never,
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
        customMeta: {
          callback: handleRowClick,
          asId: true,
        },
        size: 165,
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
          }),
        }),
      );
    }

    if (experimentsCount > 1) {
      retVal.push(
        columnHelper.group({
          id: "experiments",
          meta: {
            header: "Experiments",
          },
          header: SectionHeader,
          columns: convertColumnDataToColumn<
            ExperimentsCompare,
            ExperimentsCompare
          >(
            [
              {
                id: COLUMN_EXPERIMENT_NAME_ID,
                label: "Name",
                header: CompareExperimentsNameHeader as never,
                cell: CompareExperimentsNameCell as never,
                customMeta: {
                  experiments,
                  experimentsIds,
                },
              },
            ],
            {},
          ),
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
          }),
        }),
      );
    }

    return retVal;
  }, [
    experimentsCount,
    handleRowClick,
    datasetColumnsData,
    selectedColumns,
    outputColumnsData,
    scoresColumnsData,
    columnsOrder,
    experiments,
    experimentsIds,
    outputColumnsOrder,
    scoresColumnsOrder,
  ]);

  const columnsToExport = useMemo(() => {
    return columns
      .reduce<Array<ColumnDef<ExperimentsCompare>>>((acc, c) => {
        const subColumns = get(c, "columns");
        return acc.concat(subColumns ? subColumns : [c]);
      }, [])
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) =>
        c === COLUMN_SELECT_ID
          ? false
          : selectedColumns.includes(c) ||
            (DEFAULT_COLUMN_PINNING.left || []).includes(c),
      );
  }, [columns, selectedColumns]);

  const filterColumns = useMemo(() => {
    return [
      ...sortBy(dynamicDatasetColumns, "label").map(
        ({ id, label, columnType }) => ({
          id,
          label: `${label} (Dataset)`,
          type: columnType,
        }),
      ),
      ...FILTER_COLUMNS,
    ];
  }, [dynamicDatasetColumns]);

  const rowIndex = findIndex(rows, (row) => activeRowId === row.id);

  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  const handleRowChange = useCallback(
    (shift: number) => {
      setActiveRowId(rows[rowIndex + shift]?.id ?? "");
    },
    [rowIndex, rows, setActiveRowId],
  );

  const handleClose = useCallback(() => {
    setExpandedCommentSections(null);
    setActiveRowId("");
  }, [setActiveRowId, setExpandedCommentSections]);

  const activeRow = useMemo(
    () => find(rows, (row) => activeRowId === row.id),
    [activeRowId, rows],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const getRowHeightStyle = useCallback(
    (height: ROW_HEIGHT) => {
      let retVal = calculateHeightStyle(height);

      if (experimentsCount > 1) {
        retVal = calculateLineHeight(height, experimentsCount);
      }

      return retVal;
    },
    [experimentsCount],
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
    }),
    [handleRowClick, setExpandedCommentSections],
  );

  if (isPending || isFeedbackScoresPending || isExperimentsOutputPending) {
    return <Loader />;
  }

  return (
    <>
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
          />
        </div>
        <div className="flex items-center gap-2">
          <CompareExperimentsActionsPanel
            rows={selectedRows}
            columnsToExport={columnsToExport}
            experiments={experiments}
          />
          <Separator orientation="vertical" className="mx-1 h-4" />
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
          ></ColumnsButton>
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        getRowHeightStyle={getRowHeightStyle}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
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
          total={total}
        ></DataTablePagination>
      </PageBodyStickyContainer>
      <CompareExperimentsPanel
        experimentsCompareId={activeRowId}
        experimentsCompare={activeRow}
        experimentsIds={experimentsIds}
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

export default ExperimentItemsTab;
