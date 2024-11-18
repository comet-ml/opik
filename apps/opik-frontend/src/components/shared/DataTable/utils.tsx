import { Checkbox } from "@/components/ui/checkbox";
import React, { CSSProperties } from "react";
import {
  CellContext,
  Column,
  ColumnDef,
  ColumnDefTemplate,
} from "@tanstack/react-table";
import { ROW_HEIGHT_MAP } from "@/constants/shared";
import {
  COLUMN_ACTIONS_ID,
  COLUMN_SELECT_ID,
  ROW_HEIGHT,
} from "@/types/shared";

export const calculateHeightClass = (rowHeight: ROW_HEIGHT) => {
  return ROW_HEIGHT_MAP[rowHeight];
};

export const getCommonPinningStyles = <TData,>(
  column: Column<TData>,
): CSSProperties => {
  const isPinned = column.getIsPinned();
  const isLastLeftPinnedColumn =
    isPinned === "left" && column.getIsLastColumn("left");
  const isFirstRightPinnedColumn =
    isPinned === "right" && column.getIsFirstColumn("right");

  return {
    boxShadow: isLastLeftPinnedColumn
      ? "inset -1px 0px 0px 0px rgb(226, 232, 240)"
      : isFirstRightPinnedColumn
        ? "inset 1px 0px 0px 0px rgb(226, 232, 240)"
        : undefined,
    left: isPinned === "left" ? `${column.getStart("left")}px` : undefined,
    right: isPinned === "right" ? `${column.getAfter("right")}px` : undefined,
    position: isPinned ? "sticky" : "relative",
    zIndex: isPinned ? 1 : 0,
  };
};

export const getCommonPinningClasses = <TData,>(
  column: Column<TData>,
  isHeader: boolean = false,
): string => {
  const isPinned = column.getIsPinned();

  return isPinned ? (isHeader ? "bg-[#FBFCFD]" : "bg-white") : "";
};

export const generateSelectColumDef = <TData,>() => {
  return {
    accessorKey: COLUMN_SELECT_ID,
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

export const generateActionsColumDef = <TData,>({
  cell,
}: {
  cell: ColumnDefTemplate<CellContext<TData, unknown>>;
}) => {
  return {
    accessorKey: COLUMN_ACTIONS_ID,
    header: "",
    cell,
    size: 56,
    enableHiding: false,
    enableResizing: false,
    enableSorting: false,
  } as ColumnDef<TData>;
};
