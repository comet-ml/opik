import { ReactNode, useEffect, useMemo } from "react";
import {
  ColumnDef,
  flexRender,
  getCoreRowModel,
  RowData,
  RowSelectionState,
  useReactTable,
} from "@tanstack/react-table";
import isFunction from "lodash/isFunction";
import isEmpty from "lodash/isEmpty";

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { cn } from "@/lib/utils";
import DataTableColumnResizer from "@/components/shared/DataTable/DataTableColumnResizer";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_TYPE,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import { calculateHeightClass } from "@/components/shared/DataTable/utils";

declare module "@tanstack/react-table" {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  interface TableMeta<TData extends RowData> {
    rowHeight: ROW_HEIGHT;
    rowHeightClass: string;
  }

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  export interface ColumnMeta<TData extends RowData, TValue> {
    type?: COLUMN_TYPE;
    header?: string;
    iconType?: COLUMN_TYPE;
    verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
    custom?: object;
  }
}

interface ResizeConfig {
  enabled: boolean;
  onColumnResize?: (data: Record<string, number>) => void;
}

interface DataTableProps<TData, TValue> {
  columns: ColumnDef<TData, TValue>[];
  data: TData[];
  onRowClick?: (row: TData) => void;
  activeRowId?: string;
  resizeConfig?: ResizeConfig;
  getRowId?: (row: TData) => string;
  getRowHeightClass?: (height: ROW_HEIGHT) => string;
  rowSelection?: RowSelectionState;
  setRowSelection?: OnChangeFn<RowSelectionState>;
  rowHeight?: ROW_HEIGHT;
  noData?: ReactNode;
  autoWidth?: boolean;
}

const DataTable = <TData, TValue>({
  columns,
  data,
  onRowClick,
  activeRowId,
  resizeConfig,
  getRowId,
  getRowHeightClass = calculateHeightClass,
  rowSelection,
  setRowSelection,
  rowHeight = ROW_HEIGHT.small,
  noData,
  autoWidth = false,
}: DataTableProps<TData, TValue>) => {
  const isResizable = resizeConfig && resizeConfig.enabled;
  const isRowClickable = isFunction(onRowClick);

  const table = useReactTable({
    data,
    columns,
    getRowId,
    columnResizeMode: "onChange",
    getCoreRowModel: getCoreRowModel(),
    onRowSelectionChange: setRowSelection ? setRowSelection : undefined,
    state: {
      ...(rowSelection && { rowSelection }),
    },
    meta: {
      rowHeight,
      rowHeightClass: getRowHeightClass(rowHeight),
    },
  });

  const columnSizing = table.getState().columnSizing;
  const headers = table.getFlatHeaders();

  const columnSizeVars = useMemo(() => {
    const colSizes: { [key: string]: number } = {};
    for (let i = 0; i < headers.length; i++) {
      const header = headers[i]!;
      colSizes[`--header-${header.id}-size`] = header.getSize();
      colSizes[`--col-${header.column.id}-size`] = header.column.getSize();
    }
    return colSizes;
  }, [headers]);

  useEffect(() => {
    if (resizeConfig?.onColumnResize && !isEmpty(columnSizing)) {
      resizeConfig.onColumnResize(columnSizing);
    }
  }, [isResizable, resizeConfig, resizeConfig?.onColumnResize, columnSizing]);

  const tableStyles = useMemo(() => {
    return autoWidth
      ? {}
      : {
          ...columnSizeVars,
          minWidth: table.getTotalSize(),
        };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoWidth, columnSizeVars]);

  return (
    <div className="overflow-x-auto overflow-y-hidden rounded-md border">
      <Table style={tableStyles}>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id}>
              {headerGroup.headers.map((header) => {
                return (
                  <TableHead
                    key={header.id}
                    style={{
                      width: `calc(var(--header-${header?.id}-size) * 1px)`,
                    }}
                  >
                    {header.isPlaceholder
                      ? null
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
          ))}
        </TableHeader>
        <TableBody>
          {table.getRowModel().rows?.length ? (
            table.getRowModel().rows.map((row) => (
              <TableRow
                key={row.id}
                data-state={row.getIsSelected() && "selected"}
                {...(isRowClickable
                  ? {
                      onClick: () => onRowClick(row.original),
                    }
                  : {})}
                className={cn({
                  "cursor-pointer": isRowClickable,
                  "bg-muted/50": row.id === activeRowId,
                })}
              >
                {row.getVisibleCells().map((cell) => (
                  <TableCell
                    key={cell.id}
                    data-cell-id={cell.id}
                    style={{
                      width: `calc(var(--col-${cell.column.id}-size) * 1px)`,
                      maxWidth: `calc(var(--col-${cell.column.id}-size) * 1px)`,
                    }}
                  >
                    {flexRender(cell.column.columnDef.cell, cell.getContext())}
                  </TableCell>
                ))}
              </TableRow>
            ))
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
