import { Checkbox } from "@/components/ui/checkbox";
import React, { CSSProperties } from "react";
import {
  CellContext,
  Column,
  ColumnDef,
  ColumnDefTemplate,
  Row,
} from "@tanstack/react-table";
import {
  ROW_HEIGHT_MAP,
  TABLE_HEADER_Z_INDEX,
  TABLE_ROW_Z_INDEX,
} from "@/constants/shared";
import {
  CELL_VERTICAL_ALIGNMENT,
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
  applyStickyWorkaround = false,
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
      position: applyStickyWorkaround ? "unset" : "sticky",
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

const getRowRange = <TData,>(
  rows: Array<Row<TData>>,
  clickedRowID: string,
  previousClickedRowID: string,
) => {
  const range: Array<Row<TData>> = [];
  const processedRowsMap = {
    [clickedRowID]: false,
    [previousClickedRowID]: false,
  };
  for (const row of rows) {
    if (row.id === clickedRowID || row.id === previousClickedRowID) {
      if ("" === previousClickedRowID) {
        range.push(row);
        break;
      }

      processedRowsMap[row.id] = true;
    }
    if (
      (processedRowsMap[clickedRowID] ||
        processedRowsMap[previousClickedRowID]) &&
      !row.getIsGrouped()
    ) {
      range.push(row);
    }
    if (
      processedRowsMap[clickedRowID] &&
      processedRowsMap[previousClickedRowID]
    ) {
      break;
    }
  }

  return range;
};

export const shiftCheckboxClickHandler = <TData,>(
  event: React.MouseEvent<HTMLButtonElement>,
  context: CellContext<TData, unknown>,
  previousClickedRowID: string,
) => {
  if (event.shiftKey) {
    const { rows, rowsById: rowsMap } = context.table.getRowModel();
    const rowsToToggle = getRowRange(
      rows,
      context.row.id,
      rows.map((r) => r.id).includes(previousClickedRowID)
        ? previousClickedRowID
        : "",
    );
    const isLastSelected = !rowsMap[context.row.id]?.getIsSelected() || false;
    rowsToToggle.forEach((row) => row.toggleSelected(isLastSelected));
  }
};

export const generateSelectColumDef = <TData,>(meta?: {
  verticalAlignment?: CELL_VERTICAL_ALIGNMENT;
}) => {
  let previousSelectedRowID = "";

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
        stopClickPropagation
      >
        <Checkbox
          checked={context.row.getIsSelected()}
          disabled={!context.row.getCanSelect()}
          onCheckedChange={(value) => context.row.toggleSelected(!!value)}
          onClick={(event) => {
            event.stopPropagation();
            shiftCheckboxClickHandler(event, context, previousSelectedRowID);
            previousSelectedRowID = context.row.id;
          }}
          aria-label="Select row"
        />
      </CellWrapper>
    ),
    meta,
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
