import React, { ReactNode, useMemo } from "react";
import {
  Cell,
  ColumnDef,
  ColumnPinningState,
  ColumnSizingState,
  ColumnSort,
  ExpandedState,
  flexRender,
  getCoreRowModel,
  getExpandedRowModel,
  getGroupedRowModel,
  GroupingState,
  Row,
  RowData,
  RowSelectionState,
  useReactTable,
} from "@tanstack/react-table";
import isFunction from "lodash/isFunction";
import isBoolean from "lodash/isBoolean";

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import DataTableColumnResizer from "@/components/shared/DataTable/DataTableColumnResizer";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ColumnsStatistic,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import {
  calculateHeightStyle,
  getCommonPinningClasses,
  getCommonPinningStyles,
} from "@/components/shared/DataTable/utils";
import { TABLE_HEADER_Z_INDEX } from "@/constants/shared";
import { cn } from "@/lib/utils";

declare module "@tanstack/react-table" {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface TableMeta<TData extends RowData> {
    columnsStatistic?: ColumnsStatistic;
    rowHeight: ROW_HEIGHT;
    rowHeightStyle: React.CSSProperties;
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  export interface ColumnMeta<TData extends RowData, TValue> {
    type?: COLUMN_TYPE;
    header?: string;
    iconType?: COLUMN_TYPE;
    verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
    overrideRowHeight?: ROW_HEIGHT;
    statisticKey?: string;
    custom?: object;
  }
}

interface SortConfig {
  enabled: boolean;
  enabledMultiSorting?: boolean;
  sorting: ColumnSort[];
  setSorting: OnChangeFn<ColumnSort[]>;
}

interface ResizeConfig {
  enabled: boolean;
  columnSizing?: ColumnSizingState;
  onColumnResize?: OnChangeFn<ColumnSizingState>;
}

interface SelectionConfig {
  rowSelection?: RowSelectionState;
  setRowSelection?: OnChangeFn<RowSelectionState>;
}

interface GroupingConfig {
  groupedColumnMode: false | "reorder" | "remove";
  grouping: GroupingState;
  setGrouping?: OnChangeFn<GroupingState>;
}

interface ExpandingConfig {
  autoResetExpanded: boolean;
  expanded: ExpandedState;
  setExpanded: OnChangeFn<ExpandedState>;
}

interface DataTableProps<TData, TValue> {
  columns: ColumnDef<TData, TValue>[];
  columnsStatistic?: ColumnsStatistic;
  data: TData[];
  renderCustomRow?: (row: Row<TData>) => ReactNode | null;
  getIsCustomRow?: (row: Row<TData>) => boolean;
  activeRowId?: string;
  sortConfig?: SortConfig;
  resizeConfig?: ResizeConfig;
  selectionConfig?: SelectionConfig;
  groupingConfig?: GroupingConfig;
  expandingConfig?: ExpandingConfig;
  getRowId?: (row: TData) => string;
  getRowHeightStyle?: (height: ROW_HEIGHT) => React.CSSProperties;
  rowHeight?: ROW_HEIGHT;
  columnPinning?: ColumnPinningState;
  noData?: ReactNode;
  autoWidth?: boolean;
}

const DataTable = <TData, TValue>({
  columns,
  columnsStatistic,
  data,
  renderCustomRow,
  getIsCustomRow = () => false,
  activeRowId,
  sortConfig,
  resizeConfig,
  selectionConfig,
  groupingConfig,
  expandingConfig,
  getRowId,
  getRowHeightStyle = calculateHeightStyle,
  rowHeight = ROW_HEIGHT.small,
  columnPinning,
  noData,
  autoWidth = false,
}: DataTableProps<TData, TValue>) => {
  const isResizable = resizeConfig && resizeConfig.enabled;

  const table = useReactTable({
    data,
    columns,
    getRowId,
    columnResizeMode: "onChange",
    ...(isBoolean(groupingConfig?.groupedColumnMode) ||
    groupingConfig?.groupedColumnMode
      ? {
          groupedColumnMode: groupingConfig!.groupedColumnMode,
        }
      : {}),
    ...(isBoolean(expandingConfig?.autoResetExpanded)
      ? {
          autoResetExpanded: expandingConfig!.autoResetExpanded,
        }
      : {}),
    enableSorting: sortConfig?.enabled ?? false,
    enableMultiSort: sortConfig?.enabledMultiSorting ?? false,
    enableSortingRemoval: false,
    onSortingChange: sortConfig?.setSorting,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
    getGroupedRowModel: getGroupedRowModel(),
    onRowSelectionChange: selectionConfig?.setRowSelection,
    onGroupingChange: groupingConfig?.setGrouping,
    onExpandedChange: expandingConfig?.setExpanded,
    onColumnSizingChange: resizeConfig?.onColumnResize,
    state: {
      ...(sortConfig?.sorting && { sorting: sortConfig.sorting }),
      ...(selectionConfig?.rowSelection && {
        rowSelection: selectionConfig.rowSelection,
      }),
      ...(groupingConfig?.grouping && { grouping: groupingConfig.grouping }),
      ...(expandingConfig?.expanded && { expanded: expandingConfig.expanded }),
      ...(resizeConfig?.columnSizing && {
        columnSizing: resizeConfig.columnSizing,
      }),
    },
    initialState: {
      ...(columnPinning && { columnPinning }),
    },
    meta: {
      columnsStatistic,
      rowHeight,
      rowHeightStyle: getRowHeightStyle(rowHeight),
    },
  });

  const columnSizing = table.getState().columnSizing;
  const headers = table.getFlatHeaders();

  const cols = useMemo(() => {
    const retVal: { id: string; size: number }[] = [];
    headers.forEach((header) => {
      const index = header.column.getIndex();
      if (index !== -1) {
        retVal[index] = {
          id: header.column.id,
          size: header.column.getSize(),
        };
      }
    });
    return retVal;
    // we need columnSizing here to rebuild the cols array
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [headers, columnSizing]);

  const renderRow = (row: Row<TData>) => {
    if (isFunction(renderCustomRow) && getIsCustomRow(row)) {
      return renderCustomRow(row);
    }

    return (
      <TableRow
        key={row.id}
        data-state={row.getIsSelected() && "selected"}
        data-row-active={row.id === activeRowId}
      >
        {row.getVisibleCells().map((cell) => renderCell(row, cell))}
      </TableRow>
    );
  };

  const renderCell = (row: Row<TData>, cell: Cell<TData, unknown>) => {
    if (cell.getIsGrouped()) {
      return (
        <TableCell
          key={cell.id}
          data-cell-id={cell.id}
          colSpan={columns.length}
        >
          {flexRender(cell.column.columnDef.cell, cell.getContext())}
        </TableCell>
      );
    }

    if (cell.getIsAggregated()) {
      return null;
    }

    if (cell.getIsPlaceholder()) {
      return (
        <TableCell
          key={cell.id}
          data-cell-id={cell.id}
          style={{
            ...getCommonPinningStyles(cell.column),
          }}
          className={getCommonPinningClasses(cell.column)}
        />
      );
    }

    return (
      <TableCell
        key={cell.id}
        data-cell-id={cell.id}
        style={{
          ...getCommonPinningStyles(cell.column),
        }}
        className={getCommonPinningClasses(cell.column)}
      >
        {flexRender(cell.column.columnDef.cell, cell.getContext())}
      </TableCell>
    );
  };

  return (
    <div className="overflow-x-auto overflow-y-hidden rounded-md border">
      <Table
        style={{
          ...(!autoWidth && { minWidth: table.getTotalSize() }),
        }}
      >
        <colgroup>
          {cols.map((i) => (
            <col key={i.id} style={{ width: `${i.size}px` }} />
          ))}
        </colgroup>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup, index, groups) => {
            const isLastRow = index === groups.length - 1;

            return (
              <TableRow
                key={headerGroup.id}
                className={cn(!isLastRow && "border-b-transparent")}
              >
                {headerGroup.headers.map((header) => {
                  return (
                    <TableHead
                      key={header.id}
                      data-header-id={header.id}
                      style={{
                        zIndex: TABLE_HEADER_Z_INDEX,
                        ...getCommonPinningStyles(header.column, true),
                      }}
                      className={getCommonPinningClasses(header.column, true)}
                      colSpan={header.colSpan}
                    >
                      {header.isPlaceholder
                        ? ""
                        : flexRender(
                            header.column.columnDef.header,
                            header.getContext(),
                          )}
                      {isResizable ? (
                        <DataTableColumnResizer header={header} />
                      ) : null}
                    </TableHead>
                  );
                })}
              </TableRow>
            );
          })}
        </TableHeader>
        <TableBody>
          {table.getRowModel().rows?.length ? (
            table.getRowModel().rows.map(renderRow)
          ) : (
            <TableRow data-testid="no-data-row">
              <TableCell colSpan={columns.length}>
                {noData ? (
                  noData
                ) : (
                  <div className="flex h-28 items-center justify-center text-muted-slate">
                    No results
                  </div>
                )}
              </TableCell>
            </TableRow>
          )}
        </TableBody>
      </Table>
    </div>
  );
};

export default DataTable;
