import React, { useCallback, useMemo, useState } from "react";
import {
  JsonParam,
  NumberParam,
  StringParam,
  useQueryParam,
} from "use-query-params";
import { keepPreviousData } from "@tanstack/react-query";
import useLocalStorageState from "use-local-storage-state";
import {
  ColumnPinningState,
  ColumnSort,
  RowSelectionState,
} from "@tanstack/react-table";
import { Plus, RotateCw } from "lucide-react";
import findIndex from "lodash/findIndex";
import get from "lodash/get";

import {
  COLUMN_ID_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  ROW_HEIGHT,
} from "@/types/shared";
import {
  convertColumnDataToColumn,
  isColumnSortable,
  mapColumnDataFields,
} from "@/lib/table";
import { generateSelectColumDef } from "@/components/shared/DataTable/utils";
import Loader from "@/components/shared/Loader/Loader";
import SearchInput from "@/components/shared/SearchInput/SearchInput";
import FiltersButton from "@/components/shared/FiltersButton/FiltersButton";
import { Separator } from "@/components/ui/separator";
import { Button } from "@/components/ui/button";
import DataTableRowHeightSelector from "@/components/shared/DataTableRowHeightSelector/DataTableRowHeightSelector";
import ColumnsButton from "@/components/shared/ColumnsButton/ColumnsButton";
import DataTable from "@/components/shared/DataTable/DataTable";
import DataTableNoData from "@/components/shared/DataTableNoData/DataTableNoData";
import DataTablePagination from "@/components/shared/DataTablePagination/DataTablePagination";
import LinkCell from "@/components/shared/DataTableCells/LinkCell";
import ListCell from "@/components/shared/DataTableCells/ListCell";
import PageBodyStickyContainer from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import PageBodyStickyTableWrapper from "@/components/layout/PageBodyStickyTableWrapper/PageBodyStickyTableWrapper";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import useAnnotationQueuesList from "@/api/annotation-queues/useAnnotationQueuesList";
import { AnnotationQueue } from "@/types/annotation-queues";
import { formatDate } from "@/lib/date";

const getRowId = (d: AnnotationQueue) => d.id;

const REFETCH_INTERVAL = 30000;

const SHARED_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: "name",
    label: "Name",
    type: COLUMN_TYPE.string,
  },
  {
    id: "instructions",
    label: "Instructions",
    type: COLUMN_TYPE.string,
    size: 400,
  },
  {
    id: "items_count",
    label: "Item count",
    type: COLUMN_TYPE.number,
    accessorFn: (row) => (row.items_count ? `${row.items_count}` : "-"),
  },
  {
    id: "reviewers",
    label: "Reviewed by",
    type: COLUMN_TYPE.list,
    cell: ListCell as never,
    accessorFn: (row) => row.reviewers?.map((r) => r.username) ?? [],
  },
  {
    id: "created_at",
    label: "Created at",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.created_at),
  },
  {
    id: "last_updated_at",
    label: "Last updated",
    type: COLUMN_TYPE.time,
    accessorFn: (row) => formatDate(row.last_updated_at),
    sortable: true,
  },
  {
    id: "created_by",
    label: "Created by",
    type: COLUMN_TYPE.string,
  },
];

const DEFAULT_COLUMNS: ColumnData<AnnotationQueue>[] = [
  ...SHARED_COLUMNS,
  {
    id: "feedback_scores",
    label: "Feedback scores (avg.)",
    type: COLUMN_TYPE.numberDictionary,
    accessorFn: (row) =>
      row.feedback_scores?.reduce(
        (acc, score) => {
          acc[score.name] = score.value;
          return acc;
        },
        {} as Record<string, number>,
      ) ?? {},
  },
];

const FILTER_COLUMNS: ColumnData<AnnotationQueue>[] = [
  {
    id: COLUMN_ID_ID,
    label: "ID",
    type: COLUMN_TYPE.string,
  },
  ...SHARED_COLUMNS,
];

const DEFAULT_COLUMN_PINNING: ColumnPinningState = {
  left: [COLUMN_SELECT_ID, COLUMN_ID_ID],
  right: [],
};

const DEFAULT_SELECTED_COLUMNS: string[] = [
  "name",
  "instructions",
  "items_count",
  "reviewers",
  "created_at",
  "last_updated_at",
];

const SELECTED_COLUMNS_KEY = "annotation-queues-selected-columns";
const COLUMNS_WIDTH_KEY = "annotation-queues-columns-width";
const COLUMNS_ORDER_KEY = "annotation-queues-columns-order";
const COLUMNS_SORT_KEY = "annotation-queues-columns-sort";
const PAGINATION_SIZE_KEY = "annotation-queues-pagination-size";
const ROW_HEIGHT_KEY = "annotation-queues-row-height";

interface AnnotationQueuesTabProps {
  projectId: string;
  projectName: string;
}

export const AnnotationQueuesTab: React.FC<AnnotationQueuesTabProps> = () => {
  const [search, setSearch] = useQueryParam("search", StringParam, {
    updateType: "replaceIn",
  });
  const [page, setPage] = useQueryParam("page", NumberParam, {
    updateType: "replaceIn",
  });
  const [size, setSize] = useLocalStorageState<number>(PAGINATION_SIZE_KEY, {
    defaultValue: 25,
  });
  const [height, setHeight] = useLocalStorageState<ROW_HEIGHT>(ROW_HEIGHT_KEY, {
    defaultValue: ROW_HEIGHT.small,
  });

  const [filters = [], setFilters] = useQueryParam(
    "annotation_queues_filters",
    JsonParam,
    {
      updateType: "replaceIn",
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

  const [sortedColumns, setSortedColumns] = useLocalStorageState<ColumnSort[]>(
    COLUMNS_SORT_KEY,
    {
      defaultValue: [
        {
          id: "last_updated_at",
          desc: true,
        },
      ],
    },
  );

  const [rowSelection, setRowSelection] = useState<RowSelectionState>({});

  const [activeRowId, setActiveRowId] = useState<string>("");

  const {
    data,
    isPending: isLoading,
    refetch,
  } = useAnnotationQueuesList(
    {
      search: search ?? "",
      page: page ?? 1,
      size: size,
      filters,
    },
    {
      placeholderData: keepPreviousData,
      refetchOnMount: false,
      refetchInterval: REFETCH_INTERVAL,
    },
  );

  const rows: AnnotationQueue[] = useMemo(() => data?.content ?? [], [data]);

  const sortableBy: string[] = useMemo(
    () => data?.sortable_by ?? [],
    [data?.sortable_by],
  );

  // Variables for future detail panel implementation
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const selectedRows: AnnotationQueue[] = useMemo(() => {
    return rows.filter((row) => rowSelection[row.id]);
  }, [rowSelection, rows]);

  const handleRowClick = useCallback(
    (row?: AnnotationQueue) => {
      if (!row) return;
      setActiveRowId(row.id);
      // TODO: Navigate to annotation queue details
      console.log("Navigate to annotation queue:", row.id);
    },
    [setActiveRowId],
  );

  const columns = useMemo(() => {
    return [
      generateSelectColumDef<AnnotationQueue>(),
      mapColumnDataFields<AnnotationQueue, AnnotationQueue>({
        id: COLUMN_ID_ID,
        label: "ID",
        type: COLUMN_TYPE.string,
        cell: LinkCell as never,
        customMeta: {
          callback: handleRowClick,
          asId: true,
        },
        sortable: isColumnSortable(COLUMN_ID_ID, sortableBy),
      }),
      ...convertColumnDataToColumn<AnnotationQueue, AnnotationQueue>(
        DEFAULT_COLUMNS,
        {
          columnsOrder,
          selectedColumns,
          sortableColumns: sortableBy,
        },
      ),
    ];
  }, [handleRowClick, sortableBy, columnsOrder, selectedColumns]);

  // Variables for future export functionality
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const columnsToExport = useMemo(() => {
    return columns
      .map((c) => get(c, "accessorKey", ""))
      .filter((c) =>
        c === COLUMN_SELECT_ID
          ? false
          : selectedColumns.includes(c) ||
            (DEFAULT_COLUMN_PINNING.left || []).includes(c),
      );
  }, [columns, selectedColumns]);

  const rowIndex = findIndex(rows, (row) => activeRowId === row.id);

  // Variables for future detail panel navigation
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const hasNext = rowIndex >= 0 ? rowIndex < rows.length - 1 : false;
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const hasPrevious = rowIndex >= 0 ? rowIndex > 0 : false;

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const handleRowChange = useCallback(
    (shift: number) => handleRowClick(rows[rowIndex + shift]),
    [handleRowClick, rowIndex, rows],
  );

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const handleClose = useCallback(() => {
    setActiveRowId("");
  }, [setActiveRowId]);

  const sortConfig = useMemo(
    () => ({
      enabled: true,
      sorting: sortedColumns,
      setSorting: setSortedColumns,
    }),
    [setSortedColumns, sortedColumns],
  );

  const resizeConfig = useMemo(
    () => ({
      enabled: true,
      columnSizing: columnsWidth,
      onColumnResize: setColumnsWidth,
    }),
    [columnsWidth, setColumnsWidth],
  );

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const clearRowSelection = useCallback(() => {
    setRowSelection({});
  }, []);

  const handleNewQueue = () => {
    // TODO: Implement create new annotation queue dialog
    console.log("Create new annotation queue");
  };

  const noDataText = useMemo(() => {
    if (search) {
      return `No annotation queues found for "${search}"`;
    }
    return "No annotation queues";
  }, [search]);

  if (isLoading) {
    return <Loader />;
  }

  return (
    <>
      <PageBodyStickyContainer
        className="-mt-4 flex flex-wrap items-center justify-between gap-x-8 gap-y-2 py-4"
        direction="bidirectional"
        limitWidth
      >
        <div className="flex items-center gap-2">
          <SearchInput
            searchText={search as string}
            setSearchText={setSearch}
            placeholder="Search by name"
            className="w-[320px]"
            dimension="sm"
          />
          <FiltersButton
            columns={FILTER_COLUMNS}
            filters={filters}
            onChange={setFilters}
          />
        </div>
        <div className="flex items-center gap-2">
          <Button size="sm" onClick={handleNewQueue}>
            <Plus className="mr-2 size-4" />
            Create new queue
          </Button>
          <Separator orientation="vertical" className="mx-2 h-4" />
          <TooltipWrapper content="Refresh annotation queues list">
            <Button
              variant="outline"
              size="icon-sm"
              className="shrink-0"
              onClick={() => {
                refetch();
              }}
            >
              <RotateCw />
            </Button>
          </TooltipWrapper>
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
          />
        </div>
      </PageBodyStickyContainer>
      <DataTable
        columns={columns}
        data={rows}
        onRowClick={handleRowClick}
        activeRowId={activeRowId ?? ""}
        sortConfig={sortConfig}
        resizeConfig={resizeConfig}
        selectionConfig={{
          rowSelection,
          setRowSelection,
        }}
        getRowId={getRowId}
        rowHeight={height as ROW_HEIGHT}
        columnPinning={DEFAULT_COLUMN_PINNING}
        noData={<DataTableNoData title={noDataText} />}
        TableWrapper={PageBodyStickyTableWrapper}
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
          total={data?.total ?? 0}
        />
      </PageBodyStickyContainer>
    </>
  );
};

export default AnnotationQueuesTab;
