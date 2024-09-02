import React, { useCallback, useMemo } from "react";
import isObject from "lodash/isObject";
import findIndex from "lodash/findIndex";
import find from "lodash/find";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";

import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import { DatasetItem, ExperimentsCompare } from "@/types/datasets";
import Loader from "@/components/shared/Loader/Loader";
import useAppStore from "@/store/AppStore";
import { useDatasetIdFromURL } from "@/hooks/useDatasetIdFromURL";
import { DatasetCompareAddExperimentHeader } from "@/components/pages/DatasetCompareExperimentsPage/DatasetCompareAddExperimentHeader";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ColumnData,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import { DatasetCompareExperimentsHeader } from "@/components/pages/DatasetCompareExperimentsPage/DatasetCompareExperimentHeader";
import { DatasetCompareExperimentsCell } from "@/components/pages/DatasetCompareExperimentsPage/DatasetCompareExperimentCell";
import DatasetCompareExperimentsPanel from "@/components/pages/DatasetCompareExperimentsPage/DatasetCompareExperimentsPanel/DatasetCompareExperimentsPanel";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn } from "@/lib/table";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import TraceDetailsPanel from "@/components/shared/TraceDetailsPanel/TraceDetailsPanel";
import useExperimentById from "@/api/datasets/useExperimentById";

const getRowHeightClass = (height: ROW_HEIGHT) => {
  switch (height) {
    case ROW_HEIGHT.small:
      return "h-20";
    case ROW_HEIGHT.medium:
      return "h-60";
    case ROW_HEIGHT.large:
      return "h-[592px]";
  }
};

const SELECTED_COLUMNS_KEY = "compare-experiments-selected-columns";
const COLUMNS_WIDTH_KEY = "compare-experiments-columns-width";
const COLUMNS_ORDER_KEY = "compare-experiments-columns-order";

export const DEFAULT_COLUMNS: ColumnData<ExperimentsCompare>[] = [
  {
    id: "id",
    label: "Item ID",
    type: COLUMN_TYPE.string,
    cell: IdCell as never,
  },
  {
    id: "input",
    label: "Input",
    size: 400,
    type: COLUMN_TYPE.string,
    accessorFn: (row) =>
      isObject(row.input)
        ? JSON.stringify(row.input, null, 2)
        : row.input || "",
    cell: CodeCell as never,
  },
  {
    id: "expected_output",
    label: "Expected output",
    size: 400,
    type: COLUMN_TYPE.string,
    iconType: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.expected_output)
        ? JSON.stringify(row.expected_output, null, 2)
        : row.expected_output || "",
    cell: CodeCell as never,
  },
  {
    id: "metadata",
    label: "Metadata",
    type: COLUMN_TYPE.dictionary,
    accessorFn: (row) =>
      isObject(row.metadata)
        ? JSON.stringify(row.metadata, null, 2)
        : row.metadata || "",
    cell: CodeCell as never,
  },
  {
    id: "created_at",
    label: "Created",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
];

export const DEFAULT_SELECTED_COLUMNS: string[] = ["id", "input"];

const DatasetCompareExperimentsPage: React.FunctionComponent = () => {
  const datasetId = useDatasetIdFromURL();
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

  const [size = 10, setSize] = useQueryParam("size", NumberParam, {
    updateType: "replaceIn",
  });

  const [height = ROW_HEIGHT.small, setHeight] = useQueryParam(
    "height",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [experimentsIds = []] = useQueryParam("experiments", JsonParam, {
    updateType: "replaceIn",
  });

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

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<
      ExperimentsCompare,
      ExperimentsCompare
    >(
      DEFAULT_COLUMNS.map((c) => {
        return height === ROW_HEIGHT.small
          ? {
              ...c,
              verticalAlignment: CELL_VERTICAL_ALIGNMENT.start,
            }
          : c;
      }),
      {
        columnsWidth,
        selectedColumns,
        columnsOrder,
      },
    );

    experimentsIds.forEach((id: string) => {
      const size = columnsWidth[id] ?? 400;
      retVal.push({
        accessorKey: id,
        header: DatasetCompareExperimentsHeader,
        cell: DatasetCompareExperimentsCell as never,
        meta: {
          custom: {
            openTrace: setTraceId,
          },
        },
        size,
      });
    });

    retVal.push({
      accessorKey: "add_experiment",
      enableHiding: false,
      enableResizing: false,
      size: 48,
      header: DatasetCompareAddExperimentHeader,
    });

    return retVal;
  }, [
    columnsWidth,
    selectedColumns,
    columnsOrder,
    experimentsIds,
    setTraceId,
    height,
  ]);

  const { data, isPending } = useCompareExperimentsList(
    {
      workspaceName,
      datasetId,
      experimentsIds,
      page: page as number,
      size: size as number,
    },
    {
      placeholderData: keepPreviousData,
    },
  );

  const { data: experiment } = useExperimentById(
    {
      experimentId: experimentsIds[0],
    },
    {
      refetchOnMount: false,
      enabled: experimentsIds.length === 1,
    },
  );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = "There are no selected experiments";
  const title =
    experimentsIds.length === 1
      ? experiment?.name
      : `Compare (${experimentsIds.length})`;

  const handleRowClick = useCallback(
    (row: DatasetItem) => {
      setActiveRowId((state) => (row.id === state ? "" : row.id));
    },
    [setActiveRowId],
  );

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
      onColumnResize: setColumnsWidth,
    }),
    [setColumnsWidth],
  );

  if (isPending) {
    return <Loader />;
  }

  return (
    <div className="pt-6">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="comet-title-l">{title}</h1>
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2"></div>
        <div className="flex items-center gap-2">
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={DEFAULT_COLUMNS}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          ></ColumnsButton>
        </div>
      </div>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        resizeConfig={resizeConfig}
        rowHeight={height as ROW_HEIGHT}
        getRowHeightClass={getRowHeightClass}
        noData={<DataTableNoData title={noDataText} />}
      />
      <div className="py-4 pl-6 pr-5">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={total}
        ></DataTablePagination>
      </div>
      <DatasetCompareExperimentsPanel
        experimentsCompareId={activeRowId}
        experimentsCompare={activeRow}
        experimentsIds={experimentsIds}
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        openTrace={setTraceId as OnChangeFn<string>}
        onClose={handleClose}
        onRowChange={handleRowChange}
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

export default DatasetCompareExperimentsPage;
