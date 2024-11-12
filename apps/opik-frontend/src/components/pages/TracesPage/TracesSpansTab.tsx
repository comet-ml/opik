import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import useLocalStorageState from "use-local-storage-state";
import { RotateCw } from "lucide-react";
import findIndex from "lodash/findIndex";

import useTracesOrSpansList, {
  TRACE_DATA_TYPE,
} from "@/hooks/useTracesOrSpansList";
import { ROW_HEIGHT } from "@/types/shared";
import { RowSelectionState } from "@tanstack/react-table";
import { keepPreviousData } from "@tanstack/react-query";
import { BaseTraceData, Span, Trace } from "@/types/traces";
import {
  DEFAULT_TRACES_PAGE_COLUMNS,
  TRACES_PAGE_COLUMNS,
} from "@/constants/traces";
import { convertColumnDataToColumn } from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import NoTracesPage from "@/components/pages/TracesPage/NoTracesPage";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import TracesActionsPanel from "@/components/pages/TracesPage/TracesActionsPanel";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import TraceDetailsPanel from "@/components/shared/TraceDetailsPanel/TraceDetailsPanel";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

const getRowId = (d: Trace | Span) => d.id;

const SELECTED_COLUMNS_KEY = "traces-selected-columns";
const COLUMNS_WIDTH_KEY = "traces-columns-width";
const COLUMNS_ORDER_KEY = "traces-columns-order";

type TracesSpansTabProps = {
  type: TRACE_DATA_TYPE;
  projectId: string;
  projectName: string;
};

export const TracesSpansTab: React.FC<TracesSpansTabProps> = ({
  type,
  projectId,
  projectName,
}) => {
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
      truncate: false,
    },
    {
      placeholderData: keepPreviousData,
      refetchInterval: 30000,
    },
  );

  const noData = !search && filters.length === 0;
  const noDataText = noData
    ? `There are no ${
        type === TRACE_DATA_TYPE.traces ? "traces" : "LLM calls"
      } yet`
    : "No search results";

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

  if (noData && rows.length === 0 && page === 1) {
    return <NoTracesPage />;
  }

  return (
    <div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2">
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
        </div>
        <div className="flex items-center gap-2">
          <TracesActionsPanel
            projectId={projectId}
            projectName={projectName}
            rows={selectedRows}
            selectedColumns={selectedColumns}
            type={type as TRACE_DATA_TYPE}
          />
          <Separator orientation="vertical" className="ml-2 mr-2.5 h-6" />
          <TooltipWrapper
            content={`Refresh ${
              type === TRACE_DATA_TYPE.traces ? "traces" : "LLM calls"
            } list`}
          >
            <Button
              variant="outline"
              size="icon"
              className="shrink-0"
              onClick={() => refetch()}
            >
              <RotateCw className="size-4" />
            </Button>
          </TooltipWrapper>
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
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
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

export default TracesSpansTab;
