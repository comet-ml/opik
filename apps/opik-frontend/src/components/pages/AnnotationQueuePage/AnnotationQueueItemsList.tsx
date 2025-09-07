import React, { useCallback, useMemo } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { ColumnSort } from "@tanstack/react-table";
import useLocalStorageState from "use-local-storage-state";

import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import IdCell from "@/components/shared/DataTableCells/IdCell";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import { SelectBox } from "@/components/shared/SelectBox/SelectBox";
import { convertColumnDataToColumn, mapColumnDataFields } from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import { COLUMN_TYPE, ColumnData } from "@/types/shared";
import { formatDate } from "@/lib/date";

import {
  AnnotationQueue,
  AnnotationQueueScope,
} from "@/types/annotation-queues";
import useAppStore from "@/store/AppStore";
import useAnnotationQueueItemsList from "@/api/annotation-queues/useAnnotationQueueItemsList";
import useQueryParamAndLocalStorageState from "@/hooks/useQueryParamAndLocalStorageState";

// Storage keys for persistence
const SELECTED_COLUMNS_KEY = "annotation-queue-items-selected-columns";
const COLUMNS_WIDTH_KEY = "annotation-queue-items-columns-width";
const COLUMNS_ORDER_KEY = "annotation-queue-items-columns-order";
const COLUMNS_SORT_KEY = "annotation-queue-items-columns-sort";
const PAGINATION_SIZE_KEY = "annotation-queue-items-pagination-size";

// Column IDs
export const COLUMN_ID_ID = "id";
export const COLUMN_INPUT = "input";
export const COLUMN_OUTPUT = "output";
export const COLUMN_REVIEWED_BY = "reviewed_by";
export const COLUMN_SCORE_1 = "score_1";
export const COLUMN_SCORE_2 = "score_2";
export const COLUMN_COMMENTS = "comments";
export const COLUMN_CREATED_AT = "created_at";

export const DEFAULT_SELECTED_COLUMNS: string[] = [
  COLUMN_INPUT,
  COLUMN_OUTPUT,
  COLUMN_REVIEWED_BY,
  COLUMN_SCORE_1,
  COLUMN_SCORE_2,
  COLUMN_COMMENTS,
];

export const DEFAULT_SORTING_COLUMNS: ColumnSort[] = [
  {
    id: "created_at",
    desc: true,
  },
];

// Queue item type (for now we'll use the raw data from backend)
type QueueItem = {
  id: string;
  type: string;
  created_at: string;
  created_by: string;
  // TODO: Add more fields when backend provides them
  input?: Record<string, unknown>;
  output?: Record<string, unknown>;
  reviewed_by?: string;
  score_1?: number;
  score_2?: number;
  comments?: string;
};

export const getRowId = (item: QueueItem) => item.id;

interface AnnotationQueueItemsListProps {
  annotationQueue: AnnotationQueue;
  onItemClick?: (itemId: string, itemType: string) => void;
}

const AnnotationQueueItemsList: React.FunctionComponent<
  AnnotationQueueItemsListProps
> = ({ annotationQueue, onItemClick }) => {
  const workspaceName = useAppStore((state) => state.activeWorkspaceName);

  const [search = "", setSearch] = useQueryParam("search", StringParam, {
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
    defaultValue: 10,
    queryParamConfig: NumberParam,
    syncQueryWithLocalStorageOnInit: true,
  });

  const [rowSelection = {}, setRowSelection] = useQueryParam(
    "selection",
    JsonParam,
    {
      updateType: "replaceIn",
    },
  );

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: DEFAULT_SORTING_COLUMNS,
    },
  );

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

  const [columnsWidth, setColumnsWidth] = useLocalStorageState<
    Record<string, number>
  >(COLUMNS_WIDTH_KEY, {
    defaultValue: {},
  });

  const { data: itemsData } = useAnnotationQueueItemsList(
    {
      workspaceName,
      annotationQueueId: annotationQueue.id,
      page: page!,
      size: size!,
    },
  );

  const items: QueueItem[] = useMemo(
    () => itemsData?.content || [],
    [itemsData?.content],
  );
  const total = itemsData?.total ?? 0;
  const noData = !search;
  const noDataText = noData ? "No items in queue yet" : "No search results";

  const selectedRows: QueueItem[] = useMemo(() => {
    return items.filter((row) => rowSelection[row.id]);
  }, [rowSelection, items]);

  const getScopeLabel = () => {
    return annotationQueue.scope === AnnotationQueueScope.TRACE
      ? "Traces"
      : "Threads";
  };

  const columnsDef: ColumnData<QueueItem>[] = useMemo(() => {
    return [
      {
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        sortable: true,
      },
      {
        id: COLUMN_INPUT,
        label: "Input",
        type: COLUMN_TYPE.string,
        accessorFn: (row) =>
          row.input ? JSON.stringify(row.input).substring(0, 100) + "..." : "-",
      },
      {
        id: COLUMN_OUTPUT,
        label: "Output",
        type: COLUMN_TYPE.string,
        accessorFn: (row) =>
          row.output
            ? JSON.stringify(row.output).substring(0, 100) + "..."
            : "-",
      },
      {
        id: COLUMN_REVIEWED_BY,
        label: "Reviewed By",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.reviewed_by || "Not reviewed",
      },
      {
        id: COLUMN_SCORE_1,
        label: "Score 1",
        type: COLUMN_TYPE.number,
        accessorFn: (row) => row.score_1 ?? "-",
      },
      {
        id: COLUMN_SCORE_2,
        label: "Score 2",
        type: COLUMN_TYPE.number,
        accessorFn: (row) => row.score_2 ?? "-",
      },
      {
        id: COLUMN_COMMENTS,
        label: "Comments",
        type: COLUMN_TYPE.string,
        accessorFn: (row) => row.comments || "-",
      },
      {
        id: COLUMN_CREATED_AT,
        label: "Added",
        type: COLUMN_TYPE.time,
        accessorFn: (row) => formatDate(row.created_at),
        sortable: true,
      },
    ];
  }, []);

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<QueueItem>(),
      mapColumnDataFields<QueueItem, QueueItem>({
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: IdCell as never,
        sortable: true,
      }),
      ...convertColumnDataToColumn<QueueItem, QueueItem>(columnsDef, {
        columnsOrder,
        selectedColumns,
      }),
    ];
  }, [columnsDef, selectedColumns, columnsOrder]);

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  const handleRowClick = useCallback(
    (item: QueueItem) => {
      // Open the item details in sidebar on the same page
      if (onItemClick) {
        onItemClick(item.id, item.type);
      }
    },
    [onItemClick],
  );

  const handleAddItems = useCallback(() => {
    // TODO: Implement add items functionality
    console.log("Add items to queue");
  }, []);

  const handleRemoveItems = useCallback(() => {
    if (selectedRows.length === 0) return;
    // TODO: Implement remove items functionality
    console.log(
      "Remove items from queue:",
      selectedRows.map((item) => item.id),
    );
  }, [selectedRows]);

  const clearRowSelection = useCallback(() => {
    setRowSelection({});
  }, [setRowSelection]);

  return (
    <div className="space-y-4">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">
          Queue Items - {getScopeLabel()}
        </h3>
        <Button variant="outline" onClick={handleAddItems}>
          Add {getScopeLabel()}
        </Button>
      </div>

      {/* Toolbar */}
      <div className="flex items-center justify-between gap-4">
        <div className="flex items-center gap-4">
          <SearchInput
            searchText={search!}
            setSearchText={setSearch}
            placeholder={`Search ${getScopeLabel().toLowerCase()}...`}
            className="w-[320px]"
            dimension="sm"
          />
          <SelectBox
            value=""
            onChange={() => {}}
            placeholder="Filter by status"
            options={[
              { value: "all", label: "All" },
              { value: "pending", label: "Pending Review" },
              { value: "reviewed", label: "Reviewed" },
            ]}
          />
        </div>
        <div className="flex items-center gap-2">
          {selectedRows.length > 0 && (
            <>
              <span className="text-nowrap text-sm text-muted-foreground">
                {selectedRows.length} selected
              </span>
              <Button
                variant="outline"
                size="sm"
                className="text-red-600 hover:text-red-700"
                onClick={handleRemoveItems}
              >
                Remove Selected
              </Button>
              <Separator orientation="vertical" className="mx-2 h-4" />
            </>
          )}
          <ColumnsButton
            columns={columnsDef}
            selectedColumns={selectedColumns}
            onSelectionChange={setSelectedColumns}
            order={columnsOrder}
            onOrderChange={setColumnsOrder}
          />
        </div>
      </div>

      {/* Data Table */}
      <DataTable
        columns={columns}
        data={items}
        onRowClick={handleRowClick}
        sortConfig={{
          enabled: true,
          sorting: sortedColumns,
          setSorting: setSortedColumns,
        }}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        noData={
          <DataTableNoData title={noDataText}>
            {noData && (
              <div className="space-y-2">
                <p className="text-muted-foreground">
                  This queue doesn't contain any {getScopeLabel().toLowerCase()}{" "}
                  yet.
                </p>
                <Button variant="link" onClick={handleAddItems}>
                  Add {getScopeLabel()}
                </Button>
              </div>
            )}
          </DataTableNoData>
        }
      />

      {/* Pagination */}
      <DataTablePagination
        page={page!}
        pageChange={setPage}
        size={size!}
        sizeChange={setSize}
        total={total}
      />
    </div>
  );
};

export default AnnotationQueueItemsList;
