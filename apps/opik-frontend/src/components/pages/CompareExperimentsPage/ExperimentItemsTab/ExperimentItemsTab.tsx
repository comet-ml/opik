import React, { useCallback, useMemo } from "react";
import isObject from "lodash/isObject";
import findIndex from "lodash/findIndex";
import find from "lodash/find";
import { NumberParam, StringParam, useQueryParam } from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";

import {
  COLUMN_TYPE,
  ColumnData,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import CodeCell from "@/components/shared/DataTableCells/CodeCell";
import CompareExperimentsHeader from "@/components/pages/CompareExperimentsPage/CompareExperimentsHeader";
import CompareExperimentsCell from "@/components/pages/CompareExperimentsPage/CompareExperimentsCell";
import CompareExperimentAddHeader from "@/components/pages/CompareExperimentsPage/CompareExperimentAddHeader";
import TraceDetailsPanel from "@/components/shared/TraceDetailsPanel/TraceDetailsPanel";
import CompareExperimentsPanel from "@/components/pages/CompareExperimentsPage/CompareExperimentsPanel/CompareExperimentsPanel";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import Loader from "@/components/shared/Loader/Loader";
import useCompareExperimentsList from "@/api/datasets/useCompareExperimentsList";
import useAppStore from "@/store/AppStore";
import { ExperimentsCompare } from "@/types/datasets";
import { useDatasetIdFromCompareExperimentsURL } from "@/hooks/useDatasetIdFromCompareExperimentsURL";
import { formatDate } from "@/lib/date";
import { convertColumnDataToColumn } from "@/lib/table";

const getRowId = (d: ExperimentsCompare) => d.id;

const getRowHeightClass = (height: ROW_HEIGHT) => {
  switch (height) {
    case ROW_HEIGHT.small:
      return "h-[104px]";
    case ROW_HEIGHT.medium:
      return "h-[196px]";
    case ROW_HEIGHT.large:
      return "h-[408px]";
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
    iconType: COLUMN_TYPE.dictionary,
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

export type ExperimentItemsTabProps = {
  experimentsIds: string[];
};

const ExperimentItemsTab: React.FunctionComponent<ExperimentItemsTabProps> = ({
  experimentsIds = [],
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
    >(DEFAULT_COLUMNS, {
      columnsWidth,
      selectedColumns,
      columnsOrder,
    });

    experimentsIds.forEach((id: string) => {
      const size = columnsWidth[id] ?? 400;
      retVal.push({
        accessorKey: id,
        header: CompareExperimentsHeader,
        cell: CompareExperimentsCell as never,
        meta: {
          custom: {
            openTrace: setTraceId,
          },
        },
        size,
        minSize: 120,
      });
    });

    retVal.push({
      accessorKey: "add_experiment",
      enableHiding: false,
      enableResizing: false,
      size: 48,
      header: CompareExperimentAddHeader,
    });

    return retVal;
  }, [columnsWidth, selectedColumns, columnsOrder, experimentsIds, setTraceId]);

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
      refetchInterval: 30000,
    },
  );

  const rows = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? 0;
  const noDataText = "There is no data for the selected experiments";

  const handleRowClick = useCallback(
    (row: ExperimentsCompare) => {
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
    <div>
      <div className="mb-6 flex items-center justify-between gap-8">
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
        activeRowId={activeRowId ?? ""}
        resizeConfig={resizeConfig}
        getRowId={getRowId}
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
