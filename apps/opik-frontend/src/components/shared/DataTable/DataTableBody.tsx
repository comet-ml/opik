import React from "react";
import { TableBody } from "@/components/ui/table";
import { Row, Table } from "@tanstack/react-table";
import { cn } from "@/lib/utils";

export type DataTableBodyProps<TData> = {
  table: Table<TData>;
  renderRow: (row: Row<TData>) => React.ReactNode | null;
  renderNoData: () => React.ReactNode | null;
  showLoadingOverlay?: boolean;
};

export const DataTableBody = <TData,>({
  table,
  renderRow,
  renderNoData,
  showLoadingOverlay = false,
}: DataTableBodyProps<TData>) => {
  const rows = table.getRowModel().rows;

  return (
    <TableBody
      className={cn(showLoadingOverlay && "comet-table-body-loading-overlay")}
    >
      {rows?.length ? rows.map(renderRow) : renderNoData()}
    </TableBody>
  );
};

export default DataTableBody;
