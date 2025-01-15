import { Checkbox } from "@/components/ui/checkbox";
import React, { CSSProperties } from "react";
import {
  CellContext,
  Column,
  ColumnDef,
  ColumnDefTemplate,
} from "@tanstack/react-table";
import {
  ROW_HEIGHT_MAP,
  TABLE_HEADER_Z_INDEX,
  TABLE_ROW_Z_INDEX,
} from "@/constants/shared";
import {
  COLUMN_ACTIONS_ID,
  COLUMN_SELECT_ID,
  ROW_HEIGHT,
} from "@/types/shared";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";

export const calculateHeightStyle = (rowHeight: ROW_HEIGHT) => {
  return ROW_HEIGHT_MAP[rowHeight];
};

export const getCommonPinningStyles = <TData,>(
  column: Column<TData>,
  isHeader: boolean = false,
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
    ...(isPinned && {
      position: "sticky",
      zIndex: isHeader ? TABLE_HEADER_Z_INDEX + 1 : TABLE_ROW_Z_INDEX + 1,
    }),
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
    header: (context) => (
      <HeaderWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        supportStatistic={false}
      >
        <Checkbox
          onClick={(event) => event.stopPropagation()}
          checked={
            context.table.getIsAllPageRowsSelected() ||
            (context.table.getIsSomePageRowsSelected() && "indeterminate")
          }
          onCheckedChange={(value) =>
            context.table.toggleAllPageRowsSelected(!!value)
          }
          aria-label="Select all"
        />
      </HeaderWrapper>
    ),
    cell: (context) => (
      <CellWrapper
        metadata={context.column.columnDef.meta}
        tableMetadata={context.table.options.meta}
        className="py-3.5"
      >
        <Checkbox
          onClick={(event) => event.stopPropagation()}
          checked={context.row.getIsSelected()}
          disabled={!context.row.getCanSelect()}
          onCheckedChange={(value) => context.row.toggleSelected(!!value)}
          aria-label="Select row"
        />
      </CellWrapper>
    ),
    size: 50,
    enableResizing: false,
    enableSorting: false,
    enableHiding: false,
  } as ColumnDef<TData>;
};

export const generateActionsColumDef = <TData,>({
  cell,
  customMeta,
}: {
  cell: ColumnDefTemplate<CellContext<TData, unknown>>;
  customMeta?: unknown;
}) => {
  return {
    accessorKey: COLUMN_ACTIONS_ID,
    meta: {
      custom: customMeta,
    },
    header: "",
    cell,
    size: 56,
    enableHiding: false,
    enableResizing: false,
    enableSorting: false,
  } as ColumnDef<TData>;
};
