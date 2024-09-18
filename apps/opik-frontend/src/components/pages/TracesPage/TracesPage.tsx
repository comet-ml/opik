import React, { useCallback, useMemo, useState } from "react";
import findIndex from "lodash/findIndex";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { keepPreviousData } from "@tanstack/react-query";
import { RotateCw } from "lucide-react";
import { RowSelectionState } from "@tanstack/react-table";

import Loader from "@/components/shared/Loader/Loader";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import { BaseTraceData, Span, Trace } from "@/types/traces";
import { useProjectIdFromURL } from "@/hooks/useProjectIdFromURL";
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import {
  DEFAULT_TRACES_PAGE_COLUMNS,
  TRACES_PAGE_COLUMNS,
} from "@/constants/traces";
import TraceDetailsPanel from "@/components/shared/TraceDetailsPanel/TraceDetailsPanel";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import TracesActionsButton from "@/components/pages/TracesPage/TracesActionsButton";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import { ROW_HEIGHT } from "@/types/shared";
import { convertColumnDataToColumn } from "@/lib/table";
import { Button } from "@/components/ui/button";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const getRowId = (d: Trace | Span) => d.id;

const SELECTED_COLUMNS_KEY = "traces-selected-columns";
const COLUMNS_WIDTH_KEY = "traces-columns-width";
const COLUMNS_ORDER_KEY = "traces-columns-order";

const TracesPage = () => {
  const projectId = useProjectIdFromURL();

  const [type = TRACE_DATA_TYPE.traces, setType] = useQueryParam(
    "type",
    StringParam,
    {
      updateType: "replaceIn",
    },
  );

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
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

  const [filters = [], setFilters] = useQueryParam("filters", JsonParam, {
    updateType: "replaceIn",
  });

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const { data, isPending, refetch } = useTracesOrSpansList(
    {
      projectId,
      type: type as TRACE_DATA_TYPE,
      filters,
      page: page as number,
      size: size as number,
      search: search as string,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const noDataText = search
    ? "No search results"
    : `There are no ${
        type === TRACE_DATA_TYPE.traces ? "traces" : "LLM calls"
      } yet`;

  const rows: Array<Span | Trace> = useMemo(() => data?.content ?? [], [data]);

  const [selectedColumns, setSelectedColumns] = useLocalStorageState<string[]>(
    SELECTED_COLUMNS_KEY,
    {
      defaultValue: DEFAULT_TRACES_PAGE_COLUMNS,
    },
  );

  const [columnsOrder, setColumnsOrder] = useLocalStorageState<string[]>(
    COLUMNS_ORDER_KEY,
    {
      defaultValue: [],
    },
  );

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const selectedRows: Array<Trace | Span> = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const columns = useMemo(() => {
    const retVal = convertColumnDataToColumn<BaseTraceData, Span | Trace>(
      TRACES_PAGE_COLUMNS,
      {
        columnsOrder,
        columnsWidth,
        selectedColumns,
      },
    );

    retVal.unshift(generateSelectColumDef<Trace | Span>());

    return retVal;
  }, [selectedColumns, columnsWidth, columnsOrder]);

  const handleRowClick = useCallback(
    (row?: Trace | Span) => {
      if (!row) return;
      if (type === TRACE_DATA_TYPE.traces) {
        setTraceId((state) => (row.id === state ? "" : row.id));
        setSpanId("");
      } else {
        setTraceId((row as Span).trace_id);
        setSpanId((state) => (row.id === state ? "" : row.id));
      }
    },
    [setTraceId, setSpanId, type],
  );

  const handleTypeChange = useCallback(
    (value: TRACE_DATA_TYPE) => {
      value && setType(value);
    },
    [setType],
  );

  const activeRowId = type === TRACE_DATA_TYPE.traces ? traceId : spanId;
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
        <h1 className="comet-title-l">Traces</h1>
        <TooltipWrapper content="Refresh traces list">
          <Button variant="outline" size="icon-sm" onClick={() => refetch()}>
            <RotateCw className="size-4" />
          </Button>
        </TooltipWrapper>
      </div>
      <div className="mb-4 flex items-center justify-between gap-8">
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by ID"
            className="w-[320px]"
          ></SearchInput>
          <FiltersButton
            columns={TRACES_PAGE_COLUMNS}
            filters={filters}
            onChange={setFilters}
          />
          <ToggleGroup
            type="single"
            value={type as TRACE_DATA_TYPE}
            onValueChange={handleTypeChange}
          >
            <ToggleGroupItem value={TRACE_DATA_TYPE.traces} aria-label="Traces">
              Traces
            </ToggleGroupItem>
            <ToggleGroupItem value={TRACE_DATA_TYPE.llm} aria-label="LLM calls">
              LLM calls
            </ToggleGroupItem>
          </ToggleGroup>
        </div>
        <div className="flex items-center gap-2">
          {selectedRows.length > 0 && (
            <TracesActionsButton
              projectId={projectId}
              rows={selectedRows}
              type={type as TRACE_DATA_TYPE}
            />
          )}
          <DataTableRowHeightSelector
            type={height as ROW_HEIGHT}
            setType={setHeight}
          />
          <ColumnsButton
            columns={TRACES_PAGE_COLUMNS}
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
        rowSelection={rowSelection}
        setRowSelection={setRowSelection}
        rowHeight={height as ROW_HEIGHT}
        noData={<DataTableNoData title={noDataText} />}
      />
      <div className="py-4">
        <DataTablePagination
          page={page as number}
          pageChange={setPage}
          size={size as number}
          sizeChange={setSize}
          total={data?.total ?? 0}
        ></DataTablePagination>
      </div>
      <TraceDetailsPanel
        projectId={projectId}
        traceId={traceId as string}
        spanId={spanId as string}
        setSpanId={setSpanId}
        hasPreviousRow={hasPrevious}
        hasNextRow={hasNext}
        onClose={handleClose}
        onRowChange={handleRowChange}
      />
    </div>
  );
};

export default TracesPage;
