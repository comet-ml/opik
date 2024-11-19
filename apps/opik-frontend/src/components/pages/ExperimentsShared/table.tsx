import React from "react";
import { Checkbox } from "@/components/ui/checkbox";
import {
  CellContext,
  ColumnDef,
  flexRender,
  GroupingState,
  Row,
} from "@tanstack/react-table";
import { COLUMN_NAME_ID, ColumnData, OnChangeFn } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronUp, Text } from "lucide-react";
import { mapColumnDataFields } from "@/lib/table";
import { cn } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {
  checkIsMoreRowId,
  DEFAULT_EXPERIMENTS_PER_GROUP,
  GroupedExperiment,
  GROUPING_COLUMN,
} from "@/hooks/useGroupedExperimentsList";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";
import { TableCell, TableRow } from "@/components/ui/table";
import {
  getCommonPinningClasses,
  getCommonPinningStyles,
} from "@/components/shared/DataTable/utils";

export const GROUPING_CONFIG = {
  groupedColumnMode: false as const,
  grouping: [GROUPING_COLUMN] as GroupingState,
};

export const getRowId = (e: GroupedExperiment) => e.id;
export const getIsMoreRow = (row: Row<GroupedExperiment>) =>
  checkIsMoreRowId(row?.original?.id || "");

export const generateExperimentNameColumDef = <TData,>({
  size,
}: {
  size?: number;
  asResource?: boolean;
}) => {
  return {
    accessorKey: COLUMN_NAME_ID,
    header: ({ table }) => (
      <div
        className={cn("flex size-full items-center gap-2 pr-2")}
        onClick={(e) => e.stopPropagation()}
      >
        <Checkbox
          checked={
            table.getIsAllPageRowsSelected() ||
            (table.getIsSomePageRowsSelected() && "indeterminate")
          }
          onCheckedChange={(value) => table.toggleAllPageRowsSelected(!!value)}
          aria-label="Select all"
        />
        <Text className="ml-3 size-4 shrink-0" />
        <span className="truncate">Name</span>
      </div>
    ),
    cell: (context) => {
      const data = context.row.original as GroupedExperiment;
      return (
        <CellWrapper
          metadata={context.column.columnDef.meta}
          tableMetadata={context.table.options.meta}
        >
          <Checkbox
            style={{
              marginLeft: `${context.row.depth * 28}px`,
            }}
            onClick={(event) => event.stopPropagation()}
            checked={context.row.getIsSelected()}
            disabled={!context.row.getCanSelect()}
            onCheckedChange={(value) => context.row.toggleSelected(!!value)}
            aria-label="Select row"
          />
          <div className="ml-3 min-w-1 max-w-full">
            <ResourceLink
              id={data.dataset_id}
              name={data.name}
              resource={RESOURCE_TYPE.experiment}
              search={{
                experiments: [data.id],
              }}
            />
          </div>
        </CellWrapper>
      );
    },
    size: size ?? 180,
    minSize: 100,
    enableSorting: false,
    enableHiding: false,
  } as ColumnDef<TData>;
};

export const generateGroupedCellDef = <TData, TValue>(
  columnData: ColumnData<TData>,
) => {
  return {
    ...mapColumnDataFields(columnData),
    header: () => "",
    cell: (context: CellContext<TData, TValue>) => {
      const { row, cell } = context;
      return (
        <div className="flex size-full h-14 items-center">
          <div className="flex shrink-0 items-center">
            <Checkbox
              onClick={(event) => event.stopPropagation()}
              checked={
                row.getIsAllSubRowsSelected() ||
                (row.getIsSomeSelected() && "indeterminate")
              }
              disabled={!row.getCanSelect()}
              onCheckedChange={(value) => row.toggleSelected(!!value)}
              aria-label="Select row"
            />
            <Button
              variant="minimal"
              size="sm"
              className="-mr-3 ml-3"
              onClick={(event) => {
                row.toggleExpanded();
                event.stopPropagation();
              }}
            >
              {row.getIsExpanded() ? (
                <ChevronUp className="mr-1.5 size-4" />
              ) : (
                <ChevronDown className="mr-1.5 size-4" />
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

export const renderCustomRow = (
  row: Row<GroupedExperiment>,
  setGroupLimit: OnChangeFn<Record<string, number>>,
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
            ...getCommonPinningStyles(cell.column),
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
        <td colSpan={row.getAllCells().length} className="px-2 py-1">
          <Button
            variant="link"
            className="w-full"
            onClick={() => {
              setGroupLimit((state) => {
                return {
                  ...state,
                  [row.original.dataset_id]:
                    (state[row.original.dataset_id] ||
                      DEFAULT_EXPERIMENTS_PER_GROUP) +
                    DEFAULT_EXPERIMENTS_PER_GROUP,
                };
              });
            }}
          >
            Load {DEFAULT_EXPERIMENTS_PER_GROUP} more experiments
          </Button>
        </td>
      </tr>
    );
  }
};

export const getIsCustomRow = (row: Row<GroupedExperiment>) => {
  return getIsMoreRow(row) || row.getIsGrouped();
};
