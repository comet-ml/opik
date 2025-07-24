import React, { CSSProperties } from "react";
import {
  CellContext,
  Column,
  ColumnDef,
  ColumnDefTemplate,
  flexRender,
  Row,
} from "@tanstack/react-table";
import { ChevronDown, ChevronUp } from "lucide-react";
import isString from "lodash/isString";
import get from "lodash/get";

import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { TableCell, TableRow } from "@/components/ui/table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import TypeHeader from "@/components/shared/DataTableHeaders/TypeHeader";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";
import ExplainerIcon from "@/components/shared/ExplainerIcon/ExplainerIcon";
import Loader from "@/components/shared/Loader/Loader";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import {
  ROW_HEIGHT_MAP,
  TABLE_HEADER_Z_INDEX,
  TABLE_ROW_Z_INDEX,
} from "@/constants/shared";
import {
  CELL_VERTICAL_ALIGNMENT,
  COLUMN_ACTIONS_ID,
  COLUMN_NAME_ID,
  COLUMN_SELECT_ID,
  COLUMN_TYPE,
  ColumnData,
  OnChangeFn,
  ROW_HEIGHT,
} from "@/types/shared";
import { DEFAULT_ITEMS_PER_GROUP, GROUP_ROW_TYPE } from "@/constants/groups";
import { mapColumnDataFields } from "@/lib/table";
import {
  checkIsGroupRowType,
  checkIsRowType,
  extractIdFromRowId,
} from "@/lib/groups";

export const calculateHeightStyle = (rowHeight: ROW_HEIGHT) => {
  return ROW_HEIGHT_MAP[rowHeight];
};

export const getCommonPinningStyles = <TData,>(
  column: Column<TData>,
  isHeader: boolean = false,
  applyStickyWorkaround = false,
): CSSProperties => {
  const isPinned = column.getIsPinned();

  if (!isPinned) {
    return {};
  }

  const allColumns = column.getFlatColumns();
  const leftPinnedNonGroupedColumns = allColumns.filter(
    (col) => col.getIsPinned() === "left" && !col.getIsGrouped?.(),
  );
  const rightPinnedNonGroupedColumns = allColumns.filter(
    (col) => col.getIsPinned() === "right" && !col.getIsGrouped?.(),
  );

  const isLastLeftPinnedNonGroupedColumn =
    isPinned === "left" &&
    leftPinnedNonGroupedColumns.length > 0 &&
    leftPinnedNonGroupedColumns[leftPinnedNonGroupedColumns.length - 1].id ===
      column.id;

  const isFirstRightPinnedNonGroupedColumn =
    isPinned === "right" &&
    rightPinnedNonGroupedColumns.length > 0 &&
    rightPinnedNonGroupedColumns[0].id === column.id;

  return {
    boxShadow: isLastLeftPinnedNonGroupedColumn
      ? "inset -1px 0px 0px 0px rgb(226, 232, 240)"
      : isFirstRightPinnedNonGroupedColumn
        ? "inset 1px 0px 0px 0px rgb(226, 232, 240)"
        : undefined,
    left: isPinned === "left" ? `${column.getStart("left")}px` : undefined,
    right: isPinned === "right" ? `${column.getAfter("right")}px` : undefined,
    position: applyStickyWorkaround ? "unset" : "sticky",
    zIndex: isHeader ? TABLE_HEADER_Z_INDEX + 1 : TABLE_ROW_Z_INDEX + 1,
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

export const getRowId = <TData extends { id: string }>(row: TData) => row.id;

// The goal to use shared handler between two different helpers to draw cells in table with grouping,
// to share in closure the previousSelectedRowID between two helpers
// generateGroupedNameColumDef and generateGroupedCellDef
export const getSharedShiftCheckboxClickHandler = () => {
  let previousSelectedRowID = "";

  return <TData,>(
    event: React.MouseEvent<HTMLButtonElement>,
    context: CellContext<TData, unknown>,
  ) => {
    event.stopPropagation();
    shiftCheckboxClickHandler(event, context, previousSelectedRowID);
    previousSelectedRowID = context.row.id;
  };
};
export const getIsGroupRow = <TData extends { id: string }>(
  row: Row<TData>,
) => {
  const id = row?.original?.id || "";
  return checkIsGroupRowType(id) || row.getIsGrouped();
};

export const renderCustomRow = <TData,>(
  row: Row<TData>,
  setGroupLimit: OnChangeFn<Record<string, number>>,
  applyStickyWorkaround?: boolean,
) => {
  const rowId = row.id ?? "";

  if (row.getIsGrouped()) {
    const cells = row.getVisibleCells();

    const cell = cells.find((cell) => cell.getIsGrouped());

    if (!cell) return null;

    return (
      <TableRow key={rowId} data-state={row.getIsSelected() && "selected"}>
        <TableCell
          key={cell.id}
          data-cell-id={cell.id}
          style={{
            ...getCommonPinningStyles(
              cell.column,
              false,
              applyStickyWorkaround,
            ),
            left: "0",
            boxShadow: "inset -1px 0px 0px 0px rgb(226, 232, 240)",
          }}
          className={getCommonPinningClasses(cell.column)}
        >
          {flexRender(cell.column.columnDef.cell, cell.getContext())}
        </TableCell>
        <TableCell colSpan={cells.length - 1} />
      </TableRow>
    );
  } else if (checkIsRowType(rowId, GROUP_ROW_TYPE.MORE)) {
    const extractedRowId = extractIdFromRowId(GROUP_ROW_TYPE.MORE, rowId);
    return (
      <tr key={rowId} className="border-b">
        <td colSpan={row.getAllCells().length}>
          <Button
            variant="link"
            className="w-full"
            onClick={() => {
              setGroupLimit((state) => {
                return {
                  ...state,
                  [extractedRowId]:
                    (state[extractedRowId] || DEFAULT_ITEMS_PER_GROUP) +
                    DEFAULT_ITEMS_PER_GROUP,
                };
              });
            }}
          >
            Load {DEFAULT_ITEMS_PER_GROUP} more items
          </Button>
        </td>
      </tr>
    );
  } else if (checkIsRowType(rowId, GROUP_ROW_TYPE.PENDING)) {
    return (
      <tr key={rowId} className="border-b">
        <td colSpan={row.getAllCells().length}>
          <Loader className="h-11" message="" />
        </td>
      </tr>
    );
  } else if (checkIsRowType(rowId, GROUP_ROW_TYPE.ERROR)) {
    const error = get(row.original, "error", undefined);
    return (
      <tr key={rowId} className="border-b">
        <td colSpan={row.getAllCells().length}>
          <div className="comet-body-s flex h-11 items-center justify-center gap-1 text-light-slate">
            We’ve encountered an error fetching your data
            {isString(error) && (
              <ExplainerIcon description={error}></ExplainerIcon>
            )}
          </div>
        </td>
      </tr>
    );
  }
};

export const generateGroupedNameColumDef = <
  TData extends {
    id: string;
    dataset_id: string;
    dataset_name: string;
    name: string;
  },
>(
  checkboxClickHandler: (
    event: React.MouseEvent<HTMLButtonElement>,
    context: CellContext<TData, unknown>,
  ) => void,
  sortable: boolean = false,
  resourceType: RESOURCE_TYPE = RESOURCE_TYPE.experiment,
  searchKey: string = "experiments",
) => {
  return {
    accessorKey: COLUMN_NAME_ID,
    header: TypeHeader,
    cell: (context) => {
      const data = context.row.original as TData;
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <Checkbox
            style={{
              marginLeft: `${context.row.depth * 28}px`,
            }}
            checked={context.row.getIsSelected()}
            disabled={!context.row.getCanSelect()}
            onCheckedChange={(value) => context.row.toggleSelected(!!value)}
            onClick={(event) => checkboxClickHandler(event, context)}
            aria-label="Select row"
          />
          <div className="ml-3 min-w-1 max-w-full">
            <ResourceLink
              id={data.dataset_id}
              name={data.name}
              resource={resourceType}
              search={{
                [searchKey]: [data.id],
              }}
            />
          </div>
        </CellWrapper>
      );
    },
    size: 200,
    minSize: 100,
    enableSorting: sortable,
    enableHiding: false,
    meta: {
      header: "Name",
      headerCheckbox: true,
      type: COLUMN_TYPE.string,
    },
  } as ColumnDef<TData>;
};

export const generateGroupedCellDef = <TData, TValue>(
  columnData: ColumnData<TData>,
  checkboxClickHandler: (
    event: React.MouseEvent<HTMLButtonElement>,
    context: CellContext<TData, unknown>,
  ) => void,
) => {
  const columDataFields = mapColumnDataFields(columnData);
  const { label } = columnData;
  return {
    ...columDataFields,
    header: () => "",
    cell: (context: CellContext<TData, TValue>) => {
      const { row, cell } = context;
      return (
        <div className="flex size-full h-11 items-center">
          <div
            className="flex max-w-full items-center overflow-hidden"
            style={{ paddingLeft: `${24 + context.row.depth * 20}px` }}
          >
            <Checkbox
              checked={
                row.getIsAllSubRowsSelected() ||
                (row.getIsSomeSelected() && "indeterminate")
              }
              disabled={!row.getCanSelect()}
              onCheckedChange={(value) => row.toggleSelected(!!value)}
              onClick={(event) => checkboxClickHandler(event, context)}
              aria-label="Select row"
            />
            <Button
              variant="minimal"
              size="sm"
              className="ml-1.5"
              onClick={(event) => {
                row.toggleExpanded();
                event.stopPropagation();
              }}
            >
              {row.getIsExpanded() ? (
                <ChevronUp className="mr-1 size-4 shrink-0" />
              ) : (
                <ChevronDown className="mr-1 size-4 shrink-0" />
              )}
              {label && (
                <>
                  <TooltipWrapper content={label}>
                    <span className="max-w-56 truncate">{label}</span>
                  </TooltipWrapper>
                  :
                </>
              )}
            </Button>
          </div>
          <div className="-ml-5 min-w-4 flex-1">
            {flexRender(columDataFields.cell as never, cell.getContext())}
          </div>
        </div>
      );
    },
    size: 1,
    minSize: 1,
    enableResizing: false,
    enableSorting: false,
    enableHiding: false,
  } as ColumnDef<TData, TValue>;
};
