import React, { useMemo } from "react";
import { Table } from "@tanstack/react-table";
import { TableBody, TableCell, TableRow } from "@/ui/table";
import { Skeleton } from "@/ui/skeleton";
import { ROW_HEIGHT } from "@/types/shared";
import { ROW_HEIGHT_MAP } from "@/constants/shared";

const MAX_SKELETON_ROWS = 50;

const SCREEN_FRACTION: Record<ROW_HEIGHT, number> = {
  [ROW_HEIGHT.small]: 0.6,
  [ROW_HEIGHT.medium]: 0.65,
  [ROW_HEIGHT.large]: 0.5,
};

type DataTableSkeletonBodyProps<TData> = {
  table: Table<TData>;
};

const DataTableSkeletonBody = <TData,>({
  table,
}: DataTableSkeletonBodyProps<TData>) => {
  const columns = table.getAllLeafColumns();
  const rowHeightEnum = table.options.meta?.rowHeight ?? ROW_HEIGHT.small;
  const rowHeightStyle = table.options.meta?.rowHeightStyle;
  const defaultHeight = ROW_HEIGHT_MAP[rowHeightEnum].height as string;
  const rowHeight = parseInt(
    (rowHeightStyle?.height as string) ?? defaultHeight,
    10,
  );

  const count = useMemo(() => {
    const fraction = SCREEN_FRACTION[rowHeightEnum];
    const rows = Math.ceil((window.innerHeight * fraction) / rowHeight);
    return Math.min(rows, MAX_SKELETON_ROWS);
  }, [rowHeight, rowHeightEnum]);

  return (
    <TableBody>
      {Array.from({ length: count }, (_, rowIndex) => (
        <TableRow key={rowIndex}>
          {columns.map((column) => (
            <TableCell key={column.id}>
              <div className="flex items-center p-2" style={rowHeightStyle}>
                <Skeleton className="size-full" />
              </div>
            </TableCell>
          ))}
        </TableRow>
      ))}
    </TableBody>
  );
};

export default DataTableSkeletonBody;
