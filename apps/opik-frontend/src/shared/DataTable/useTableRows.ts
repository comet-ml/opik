import { useMemo } from "react";
import { Table } from "@tanstack/react-table";

const useTableRows = <TData>(table: Table<TData>) => {
  const rowModel = table.getRowModel().rows;
  const topRows = table.getTopRows();
  const centerRows = table.getCenterRows();

  return useMemo(
    () => (topRows.length ? [...topRows, ...centerRows] : rowModel),
    [rowModel, topRows, centerRows],
  );
};

export default useTableRows;
