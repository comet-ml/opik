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

import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { TableCell, TableRow } from "@/components/ui/table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import TypeHeader from "@/components/shared/DataTableHeaders/TypeHeader";
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
import {
  DEFAULT_ITEMS_PER_GROUP,
  GROUPING_KEY,
  MORE_ROW_PREFIX,

  // TODO lala check import
} from "@/constants/groups";
import { mapColumnDataFields } from "@/lib/table";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink"; // TODO lala check import

// TODO lala check better place for type definition

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

export const buildGroupFieldsName = (name: string) => {
  return `${GROUPING_KEY}${name}`;
};

export const buildMoreRowId = (id: string) => {
  return `${MORE_ROW_PREFIX}${id}`;
};

export const checkIsMoreRowId = (id: string) => {
  return id.startsWith(MORE_ROW_PREFIX);
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
  return checkIsMoreRowId(row?.original?.id || "") || row.getIsGrouped();
};

export const renderCustomRow = <TData extends { dataset_id: string }>(
  row: Row<TData>,
  setGroupLimit: OnChangeFn<Record<string, number>>,
  applyStickyWorkaround?: boolean,
) => {
  if (row.getIsGrouped()) {
    const cells = row.getVisibleCells();

    const cell = cells.find((cell) => cell.getIsGrouped());

    if (!cell) return null;

    return (
      <TableRow key={row.id} data-state={row.getIsSelected() && "selected"}>
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
            boxShadow: undefined,
          }}
          className={getCommonPinningClasses(cell.column)}
        >
          {flexRender(cell.column.columnDef.cell, cell.getContext())}
        </TableCell>
        <TableCell
          style={{
            width: "0",
            boxShadow: "inset -1px 0px 0px 0px rgb(226, 232, 240)",
          }}
        />

        <TableCell colSpan={cells.length - 2} />
      </TableRow>
    );
  } else {
    return (
      <tr key={row.id} className="border-b">
        <td colSpan={row.getAllCells().length}>
          <Button
            variant="link"
            className="w-full"
            onClick={() => {
              setGroupLimit((state) => {
                return {
                  ...state,
                  [row.original.dataset_id]:
                    (state[row.original.dataset_id] ||
                      DEFAULT_ITEMS_PER_GROUP) + DEFAULT_ITEMS_PER_GROUP,
                };
              });
            }}
          >
            Load {DEFAULT_ITEMS_PER_GROUP} more items
          </Button>
        </td>
      </tr>
    );
  }
};

// TODO lala render item inside the grouped column
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
    size: 180,
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
  return {
    ...columDataFields,
    header: () => "",
    cell: (context: CellContext<TData, TValue>) => {
      const { row, cell } = context;
      return (
        <div className="flex size-full h-11 items-center">
          <div className="flex shrink-0 items-center pl-5">
            <Checkbox
              style={{
                marginLeft: `${context.row.depth * 28}px`,
              }}
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
              className="-mr-3 ml-1.5"
              onClick={(event) => {
                row.toggleExpanded();
                event.stopPropagation();
              }}
            >
              {row.getIsExpanded() ? (
                <ChevronUp className="mr-1 size-4" />
              ) : (
                <ChevronDown className="mr-1 size-4" />
              )}
            </Button>
          </div>
          <div className="min-w-1 flex-1">
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
