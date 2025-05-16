import React, { useCallback, useMemo } from "react";
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
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_CREATED_AT_ID,
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
import CompareExperimentsOutputCell from "@/components/pages-shared/experiments/CompareExperimentsOutputCell/CompareExperimentsOutputCell";
import CompareExperimentsFeedbackScoreCell from "@/components/pages-shared/experiments/CompareExperimentsFeedbackScoreCell/CompareExperimentsFeedbackScoreCell";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import CompareExperimentsNameCell from "@/components/pages-shared/experiments/CompareExperimentsNameCell/CompareExperimentsNameCell";
import CompareExperimentsNameHeader from "@/components/pages-shared/experiments/CompareExperimentsNameHeader/CompareExperimentsNameHeader";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { formatDate } from "@/lib/date";
import {
  convertColumnDataToColumn,
  hasAnyVisibleColumns,
  mapColumnDataFields,
} from "@/lib/table";
import { mapDynamicColumnTypesToColumnType } from "@/lib/filters";
import useCompareExperimentsColumns from "@/api/datasets/useCompareExperimentsColumns";
import { useDynamicColumnsCache } from "@/hooks/useDynamicColumnsCache";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";
import FeedbackScoreHeader from "@/components/shared/DataTableHeaders/FeedbackScoreHeader";
import ExperimentsFeedbackScoresSelect from "@/components/pages-shared/experiments/ExperimentsFeedbackScoresSelect/ExperimentsFeedbackScoresSelect";
import { calculateHeightStyle } from "@/components/shared/DataTable/utils";
import { calculateLineHeight } from "@/lib/experiments";
import SectionHeader from "@/components/shared/DataTableHeaders/SectionHeader";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";

const getRowId = (d: ExperimentsCompare) => d.id;

const calculateVerticalAlignment = (count: number) =>
  count === 1 ? undefined : CELL_VERTICAL_ALIGNMENT.start;

const columnHelper = createColumnHelper<ExperimentsCompare>();

const REFETCH_INTERVAL = 30000;
const COLUMN_EXPERIMENT_NAME_ID = "experiment_name";

const SELECTED_COLUMNS_KEY = "compare-trials-selected-columns";
const COLUMNS_WIDTH_KEY = "compare-trials-columns-width";
const COLUMNS_ORDER_KEY = "compare-trials-columns-order";
const DYNAMIC_COLUMNS_KEY = "compare-trials-dynamic-columns";
const COLUMNS_SCORES_ORDER_KEY = "compare-trials-scores-columns-order";
const COLUMNS_OUTPUT_ORDER_KEY = "compare-trials-output-columns-order";
const PAGINATION_SIZE_KEY = "compare-trials-pagination-size";
const ROW_HEIGHT_KEY = "compare-trials-row-height";

export const FILTER_COLUMNS: ColumnData<ExperimentsCompare>[] = [
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
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = ["id", "objective_name"];

export type TrialItemsTabProps = {
  objectiveName?: string;
  datasetId: string;
  experimentsIds: string[];
  experiments?: Experiment[];
};

const TrialItemsTab: React.FC<TrialItemsTabProps> = ({
  objectiveName,
  datasetId,
  experimentsIds = [],
  experiments,
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

  const experimentsCount = experimentsIds.length;
  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = "There is no data for the selected trials";

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
    ];
  }, [dynamicOutputColumns, experiments, experimentsIds, setTraceId]);

  const scoresColumnsData = useMemo(() => {
    return [
      {
        id: "objective_name",
        label: objectiveName,
        type: COLUMN_TYPE.numberDictionary,
        header: FeedbackScoreHeader as never,
        cell: CompareExperimentsFeedbackScoreCell as never,
        customMeta: {
          experimentsIds,
          feedbackKey: objectiveName,
        },
      },
    ] as ColumnData<ExperimentsCompare>[];
  }, [experimentsIds, objectiveName]);

  const columns = useMemo(() => {
    const retVal = [
      mapColumnDataFields<ExperimentsCompare, ExperimentsCompare>({
        id: COLUMN_ID_ID,
        label: "ID (Dataset item)",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        verticalAlignment: calculateVerticalAlignment(experimentsCount),
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
            header: "Optimizations scores",
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
        resizeConfig={resizeConfig}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        getRowHeightStyle={getRowHeightStyle}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
        TableBody={DataTableVirtualBody}
        stickyHeader
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
