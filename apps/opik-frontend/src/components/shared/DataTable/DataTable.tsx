import React, { ReactNode, useMemo, useState } from "react";
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
  TableMeta,
  useReactTable,
} from "@tanstack/react-table";
import isFunction from "lodash/isFunction";
import isBoolean from "lodash/isBoolean";

import {
  Table,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import DataTableColumnResizer from "@/components/shared/DataTable/DataTableColumnResizer";
import DataTableTooltipContext from "@/components/shared/DataTable/DataTableTooltipContext";
import DataTableWrapper, {
  DataTableWrapperProps,
} from "@/components/shared/DataTable/DataTableWrapper";
import DataTableBody, {
  DataTableBodyProps,
} from "@/components/shared/DataTable/DataTableBody";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  ColumnsStatistic,
  HeaderIconType,
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
import {
  STICKY_ATTRIBUTE_VERTICAL,
  STICKY_DIRECTION,
} from "@/components/layout/PageBodyStickyContainer/PageBodyStickyContainer";
import { useObserveResizeNode } from "@/hooks/useObserveResizeNode";
import useCustomRowClick from "@/components/shared/DataTable/useCustomRowClick";

declare module "@tanstack/react-table" {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface TableMeta<TData extends RowData> {
    columnsStatistic?: ColumnsStatistic;
    rowHeight: ROW_HEIGHT;
    rowHeightStyle: React.CSSProperties;
    onCommentsReply?: (row: TData, idx?: number) => void;
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  export interface ColumnMeta<TData extends RowData, TValue> {
    type?: COLUMN_TYPE;
    header?: string;
    headerCheckbox?: boolean;
    iconType?: HeaderIconType;
    verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
    overrideRowHeight?: ROW_HEIGHT;
    statisticKey?: string;
    statisticDataFormater?: (value: number) => string;
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
  onRowClick?: (row: TData) => void;
  renderCustomRow?: (
    row: Row<TData>,
    stickyWorkaround?: boolean,
  ) => ReactNode | null;
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
  stickyHeader?: boolean;
  TableWrapper?: React.FC<DataTableWrapperProps>;
  TableBody?: React.FC<DataTableBodyProps<TData>>;
  meta?: Omit<
    TableMeta<TData>,
    "columnsStatistic" | "rowHeight" | "rowHeightStyle"
  >;
}

const DataTable = <TData, TValue>({
  columns,
  columnsStatistic,
  data,
  onRowClick,
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
  TableWrapper = DataTableWrapper,
  TableBody = DataTableBody,
  stickyHeader = false,
  meta,
}: DataTableProps<TData, TValue>) => {
  const isResizable = resizeConfig && resizeConfig.enabled;
  const isRowClickable = isFunction(onRowClick);

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
      ...meta,
    },
  });

  const { onClick } = useCustomRowClick<TData>({
    onRowClick: onRowClick,
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

  const [tableHeight, setTableHeight] = useState(0);
  const [hasHorizontalScroll, setHasHorizontalScroll] = useState(false);
  const { ref: tableRef } = useObserveResizeNode<HTMLTableElement>((node) => {
    setTableHeight(node.clientHeight);
    setHasHorizontalScroll(
      node.parentElement!.scrollWidth > node?.parentElement!.clientWidth,
    );
  });

  // TODO move this workaround to context that wil be added to handle columns width
  // In the version of Chrome Version 134.0.6998.89 (Official Build) (arm64)
  // was introduced an issue that border (from row) for sticky cells is not presented on some displays
  // this workaround checks if table can be scrolled and only then allow the library to set position sticky for cells
  const stickyBorderWorkaround = !hasHorizontalScroll && !stickyHeader;

  const renderRow = (row: Row<TData>) => {
    if (isFunction(renderCustomRow) && getIsCustomRow(row)) {
      return renderCustomRow(row, stickyBorderWorkaround);
    }

    return (
      <TableRow
        key={row.id}
        data-state={row.getIsSelected() && "selected"}
        data-row-active={row.id === activeRowId}
        data-row-id={row.id}
        className={cn({
          "cursor-pointer": isRowClickable,
        })}
        {...(isRowClickable && !row.getIsGrouped()
          ? {
              onClick: (e) => onClick?.(e, row.original),
            }
          : {})}
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
            ...getCommonPinningStyles(
              cell.column,
              false,
              stickyBorderWorkaround,
            ),
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
          ...getCommonPinningStyles(cell.column, false, stickyBorderWorkaround),
        }}
        className={getCommonPinningClasses(cell.column)}
      >
        {flexRender(cell.column.columnDef.cell, cell.getContext())}
      </TableCell>
    );
  };

  const renderNoData = () => {
    return (
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
    );
  };

  return (
    <TableWrapper>
      <DataTableTooltipContext>
        <Table
          ref={tableRef}
          style={{
            ...(!autoWidth && { minWidth: table.getTotalSize() }),
            ...(tableHeight && { "--data-table-height": `${tableHeight}px` }),
          }}
        >
          <colgroup>
            {cols.map((i) => (
              <col key={i.id} style={{ width: `${i.size}px` }} />
            ))}
          </colgroup>
          <TableHeader
            className={cn(stickyHeader && "sticky z-10")}
            {...(stickyHeader && {
              ...{ [STICKY_ATTRIBUTE_VERTICAL]: STICKY_DIRECTION.vertical },
            })}
          >
            {table.getHeaderGroups().map((headerGroup, index, groups) => {
              const isLastRow = index === groups.length - 1;

              return (
                <TableRow
                  key={headerGroup.id}
                  className={cn(
                    "bg-soft-background",
                    !isLastRow && "!border-b-0",
                  )}
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
          <TableBody
            table={table}
            renderRow={renderRow}
            renderNoData={renderNoData}
          />
        </Table>
      </DataTableTooltipContext>
    </TableWrapper>
  );
};

export default DataTable;
