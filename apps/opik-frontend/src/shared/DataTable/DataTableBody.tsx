import React from "react";
import { TableBody } from "@/ui/table";
import { Row, Table } from "@tanstack/react-table";
import useTableRows from "@/shared/DataTable/useTableRows";
import { cn } from "@/lib/utils";

export type RowVirtualizationConfig = {
  enabled?: boolean;
};

export type DataTableBodyProps<TData> = {
  table: Table<TData>;
  renderRow: (row: Row<TData>) => React.ReactNode | null;
  renderNoData: () => React.ReactNode | null;
  showLoadingOverlay?: boolean;
  rowVirtualization?: RowVirtualizationConfig;
};

export const DataTableBody = <TData,>({
  table,
  renderRow,
  renderNoData,
  showLoadingOverlay = false,
}: DataTableBodyProps<TData>) => {
  const rows = useTableRows(table);

  return (
    <TableBody
      className={cn(showLoadingOverlay && "comet-table-body-loading-overlay")}
    >
      {rows?.length ? rows.map(renderRow) : renderNoData()}
    </TableBody>
  );
};

export default DataTableBody;
