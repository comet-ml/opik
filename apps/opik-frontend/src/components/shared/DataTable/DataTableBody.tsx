import React from "react";
import { TableBody } from "@/components/ui/table";
import { Row, Table } from "@tanstack/react-table";

export type DataTableBodyProps<TData> = {
  table: Table<TData>;
  renderRow: (row: Row<TData>) => React.ReactNode | null;
  renderNoData: () => React.ReactNode | null;
};

export const DataTableBody = <TData,>({
  table,
  renderRow,
  renderNoData,
}: DataTableBodyProps<TData>) => {
  const rows = table.getRowModel().rows;

  return (
    <TableBody>{rows?.length ? rows.map(renderRow) : renderNoData()}</TableBody>
  );
};

export default DataTableBody;
