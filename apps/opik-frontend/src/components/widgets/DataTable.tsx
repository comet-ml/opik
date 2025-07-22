import React, { useState } from 'react';
import {
  flexRender,
  getCoreRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  SortingState,
  ColumnDef,
} from '@tanstack/react-table';
import { ArrowUpDown, ChevronLeft, ChevronRight } from 'lucide-react';
import BaseWidget from './BaseWidget';
import { Button } from '@/components/ui/button';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { BaseWidgetProps, TableData } from '@/types/widget';
import { formatTimestamp } from '@/utils/chartHelpers';

interface DataTableProps extends BaseWidgetProps {
  data: TableData[];
  columns?: ColumnDef<TableData>[];
  pagination?: {
    page: number;
    totalPages: number;
    totalItems: number;
  };
}

const DataTable: React.FC<DataTableProps> = ({
  id,
  title,
  loading,
  error,
  data,
  columns,
  pagination,
  onEdit,
  onDelete,
  onRefresh,
}) => {
  const [sorting, setSorting] = useState<SortingState>([]);

  // Default columns if none provided
  const defaultColumns: ColumnDef<TableData>[] = React.useMemo(() => {
    if (!data || data.length === 0) return [];
    
    const firstRow = data[0];
    return Object.keys(firstRow).map(key => ({
      accessorKey: key,
      header: ({ column }) => (
        <Button
          variant="ghost"
          onClick={() => column.toggleSorting(column.getIsSorted() === "asc")}
          className="h-auto p-0 font-semibold"
        >
          {key.charAt(0).toUpperCase() + key.slice(1)}
          <ArrowUpDown className="ml-2 h-4 w-4" />
        </Button>
      ),
      cell: ({ row }) => {
        const value = row.getValue(key);
        
        // Format timestamps
        if (key.includes('timestamp') || key.includes('date') || key.includes('time')) {
          return formatTimestamp(value as string);
        }
        
        // Format status with colors
        if (key === 'status') {
          const statusColors = {
            success: 'text-green-600',
            error: 'text-red-600',
            warning: 'text-yellow-600',
            pending: 'text-blue-600',
          };
          return (
            <span className={statusColors[value as keyof typeof statusColors] || ''}>
              {value as string}
            </span>
          );
        }
        
        return value as string;
      },
    }));
  }, [data]);

  const tableColumns = columns || defaultColumns;

  const table = useReactTable({
    data: data || [],
    columns: tableColumns,
    getCoreRowModel: getCoreRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
    getSortedRowModel: getSortedRowModel(),
    onSortingChange: setSorting,
    state: {
      sorting,
    },
  });

  return (
    <BaseWidget
      id={id}
      title={title}
      loading={loading}
      error={error}
      onEdit={onEdit}
      onDelete={onDelete}
      onRefresh={onRefresh}
      className="group"
    >
      {!data || data.length === 0 ? (
        <div className="flex items-center justify-center h-32 text-muted-foreground">
          No data available
        </div>
      ) : (
        <div className="space-y-4">
          <div className="border rounded-lg">
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((headerGroup) => (
                  <TableRow key={headerGroup.id}>
                    {headerGroup.headers.map((header) => (
                      <TableHead key={header.id} className="font-semibold">
                        {header.isPlaceholder
                          ? null
                          : flexRender(
                              header.column.columnDef.header,
                              header.getContext()
                            )}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows?.length ? (
                  table.getRowModel().rows.map((row) => (
                    <TableRow
                      key={row.id}
                      data-state={row.getIsSelected() && "selected"}
                    >
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id}>
                          {flexRender(
                            cell.column.columnDef.cell,
                            cell.getContext()
                          )}
                        </TableCell>
                      ))}
                    </TableRow>
                  ))
                ) : (
                  <TableRow>
                    <TableCell
                      colSpan={tableColumns.length}
                      className="h-24 text-center"
                    >
                      No results.
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
          </div>
          
          {/* Pagination */}
          <div className="flex items-center justify-between">
            <div className="text-sm text-muted-foreground">
              {pagination ? (
                `Page ${pagination.page} of ${pagination.totalPages} (${pagination.totalItems} total)`
              ) : (
                `Showing ${table.getFilteredRowModel().rows.length} rows`
              )}
            </div>
            <div className="flex items-center space-x-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => table.previousPage()}
                disabled={!table.getCanPreviousPage()}
              >
                <ChevronLeft className="h-4 w-4" />
                Previous
              </Button>
              <Button
                variant="outline"
                size="sm"
                onClick={() => table.nextPage()}
                disabled={!table.getCanNextPage()}
              >
                Next
                <ChevronRight className="h-4 w-4" />
              </Button>
            </div>
          </div>
        </div>
      )}
    </BaseWidget>
  );
};

export default DataTable;
