import React, { useCallback, useMemo, useState } from "react";
import findIndex from "lodash/findIndex";
import find from "lodash/find";
import get from "lodash/get";
import sortBy from "lodash/sortBy";
import {
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
  COLUMN_FEEDBACK_SCORES_ID,
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  DynamicColumn,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import AutodetectCell from "@/components/shared/DataTableCells/AutodetectCell";
import CompareExperimentsOutputCell from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsOutputCell";
import CompareExperimentsFeedbackScoreCell from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsFeedbackScoreCell";
import TraceDetailsPanel from "@/components/pages-shared/traces/TraceDetailsPanel/TraceDetailsPanel";
import CompareExperimentsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsPanel";
import CompareExperimentsActionsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsActionsPanel";
import CompareExperimentsNameCell from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsNameCell";
import CompareExperimentsNameHeader from "@/components/pages/CompareExperimentsPage/ExperimentItemsTab/CompareExperimentsNameHeader";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import Loader from "@/components/shared/Loader/Loader";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { Experiment, ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
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
import ExperimentsFeedbackScoresAutocomplete from "@/components/pages-shared/experiments/ExperimentsFeedbackScoresAutocomplete/ExperimentsFeedbackScoresAutocomplete";
import {
  calculateHeightStyle,
  generateSelectColumDef,
} from "@/components/shared/DataTable/utils";
import { calculateLineHeight } from "@/components/pages/CompareExperimentsPage/helpers";
import SectionHeader from "@/components/shared/DataTableHeaders/SectionHeader";

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

export const FILTER_COLUMNS: ColumnData<ExperimentsCompare>[] = [
  {
    id: COLUMN_FEEDBACK_SCORES_ID,
    label: "Feedback scores",
    type: COLUMN_TYPE.numberDictionary,
  },
  {
    id: "output",
    label: "Evaluation task",
    type: COLUMN_TYPE.string,
  },
];

export const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

export const DEFAULT_SELECTED_COLUMNS: string[] = ["id"];

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

  const [traceId = "", setTraceId] = useQueryParam("trace", StringParam, {
    updateType: "replaceIn",
  });

  const [spanId = "", setSpanId] = useQueryParam("span", StringParam, {
    updateType: "replaceIn",
  });

  const [page = 1, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });

  const [size = 100, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });

  const [height = ROW_HEIGHT.small, setHeight] = useQueryParam(
    "height",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const filtersConfig = useMemo(
    () => ({
      rowsMap: {
        [COLUMN_FEEDBACK_SCORES_ID]: {
          keyComponent: ExperimentsFeedbackScoresAutocomplete,
          keyComponentProps: {
            experimentsIds,
            placeholder: "Feedback score",
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
        id: `output.${c.name}`,
        label: c.name,
        columnType: mapDynamicColumnTypesToColumnType(c.types),
      }));
  }, [experimentsOutputData]);

  const dynamicScoresColumns = useMemo(() => {
    return (feedbackScoresData?.scores ?? [])
      .sort((c1, c2) => c1.name.localeCompare(c2.name))
      .map<DynamicColumn>((c) => ({
        id: `feedback_scores.${c.name}`,
        label: c.name,
        columnType: COLUMN_TYPE.number,
      }));
  }, [feedbackScoresData?.scores]);

  const dynamicColumnsIds = useMemo(
    () => [
      ...dynamicDatasetColumns.map((c) => c.id),
      ...dynamicOutputColumns.map((c) => c.id),
      ...dynamicScoresColumns.map((c) => c.id),
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
        id: "created_at",
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
    return dynamicOutputColumns.map(
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
    );
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

    if (experimentsCount === 1) {
      retVal.unshift(generateSelectColumDef<ExperimentsCompare>());
    }

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
    return experimentsCount === 1
      ? columns
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
          )
      : undefined;
  }, [columns, selectedColumns, experimentsCount]);

  const filterColumns = useMemo(() => {
    const retVal: ColumnData<ExperimentsCompare>[] = [...FILTER_COLUMNS];

    sortBy(dynamicDatasetColumns, "label").forEach(
      ({ id, label, columnType }) => {
        retVal.push({
          id,
          label: `${label} (Dataset)`,
          type: columnType,
        });
      },
    );

    return retVal;
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

  const handleClose = useCallback(() => setActiveRowId(""), [setActiveRowId]);

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
    scoresColumnsData,
    scoresColumnsOrder,
    setScoresColumnsOrder,
    outputColumnsData,
    outputColumnsOrder,
    setOutputColumnsOrder,
  ]);

  if (isPending || isFeedbackScoresPending || isExperimentsOutputPending) {
    return <Loader />;
  }

  return (
    <div>
      <div className="mb-6 flex items-center justify-between gap-8">
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
            experimentName={experiments?.[0]?.name}
          />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
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
      </div>
      <DataTable
        columns={columns}
        data={rows}
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
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={total}
        ></DataTablePagination>
      </div>
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
        traceId={traceId as string}
        spanId={spanId as string}
        setSpanId={setSpanId}
        onClose={() => {
          setTraceId("");
        }}
      />
    </div>
  );
};

export default ExperimentItemsTab;
