import { Checkbox } from "@/components/ui/checkbox";
import React from "react";
import { ColumnDef } from "@tanstack/react-table";
import { ROW_HEIGHT_MAP } from "@/constants/shared";
import { ROW_HEIGHT } from "@/types/shared";

export const calculateHeightClass = (rowHeight: ROW_HEIGHT) => {
  return ROW_HEIGHT_MAP[rowHeight];
};

export const generateSelectColumDef = <TData,>() => {
  return {
    accessorKey: "select",
    header: ({ table }) => (
      <Checkbox
        onClick={(event) => event.stopPropagation()}
        checked={
          table.getIsAllPageRowsSelected() ||
          (table.getIsSomePageRowsSelected() && "indeterminate")
        }
        onCheckedChange={(value) => table.toggleAllPageRowsSelected(!!value)}
        aria-label="Select all"
      />
    ),
    cell: ({ row }) => (
      <Checkbox
        style={{
          marginLeft: `${row.depth * 4}px`,
        }}
        onClick={(event) => event.stopPropagation()}
        checked={row.getIsSelected()}
        disabled={!row.getCanSelect()}
        onCheckedChange={(value) => row.toggleSelected(!!value)}
        aria-label="Select row"
      />
    ),
    size: 50,
    enableResizing: false,
    enableSorting: false,
    enableHiding: false,
  } as ColumnDef<TData>;
};
