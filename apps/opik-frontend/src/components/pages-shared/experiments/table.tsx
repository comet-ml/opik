import React from "react";
import { Checkbox } from "@/components/ui/checkbox";
import {
  CellContext,
  ColumnDef,
  flexRender,
  GroupingState,
  Row,
} from "@tanstack/react-table";
import { ChevronDown, ChevronUp } from "lucide-react";

import {
  COLUMN_NAME_ID,
  COLUMN_TYPE,
  ColumnData,
  OnChangeFn,
} from "@/types/shared";
import { Button } from "@/components/ui/button";
import { mapColumnDataFields } from "@/lib/table";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import TypeHeader from "@/components/shared/DataTableHeaders/TypeHeader";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import { TableCell, TableRow } from "@/components/ui/table";
import {
  getCommonPinningClasses,
  getCommonPinningStyles,
  shiftCheckboxClickHandler,
} from "@/components/shared/DataTable/utils";
import { DEFAULT_ITEMS_PER_GROUP, GROUPING_COLUMN } from "@/constants/grouping";

export const GROUPING_CONFIG = {
  groupedColumnMode: false as const,
  grouping: [GROUPING_COLUMN] as GroupingState,
};

export const checkIsMoreRowId = (id: string) => {
  return /^more_/.test(id);
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
  return {
    ...mapColumnDataFields(columnData),
    header: () => "",
    cell: (context: CellContext<TData, TValue>) => {
      const { row, cell } = context;
      return (
        <div className="flex size-full h-11 items-center">
          <div className="flex shrink-0 items-center pl-5">
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
              Dataset:
            </Button>
          </div>
          <div className="min-w-1 flex-1">
            {flexRender(columnData.cell as never, cell.getContext())}
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

export const renderCustomRow = <TData extends { dataset_id: string }>(
  row: Row<TData>,
  setGroupLimit: OnChangeFn<Record<string, number>>,
  applyStickyWorkaround?: boolean,
) => {
  if (row.getIsGrouped()) {
    const cells = row.getVisibleCells();
    const cell = cells.find((cell) => cell.column.id === GROUPING_COLUMN);
    const nameCell = cells.find((cell) => cell.column.id === COLUMN_NAME_ID);

    if (!cell || !nameCell) return null;

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

export const getIsCustomRow = <TData extends { id: string }>(
  row: Row<TData>,
) => {
  return checkIsMoreRowId(row?.original?.id || "") || row.getIsGrouped();
};
