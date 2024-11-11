import { Checkbox } from "@/components/ui/checkbox";
import React from "react";
import { CellContext, ColumnDef, flexRender } from "@tanstack/react-table";
import { ColumnData } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronUp, Text } from "lucide-react";
import { mapColumnDataFields } from "@/lib/table";
import { cn } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";

export const generateExperimentNameColumDef = <TData,>() => {
  return {
    accessorKey: "name",
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
        <Text className="ml-3.5 size-4 shrink-0" />
        <span className="truncate">Name</span>
      </div>
    ),
    cell: (context) => (
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
        <span className="ml-6 truncate">{context.getValue() as string}</span>
      </CellWrapper>
    ),
    size: 180,
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
        <div className="flex size-full items-center">
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
              className="ml-4"
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
    size: 0,
    minSize: 0,
    enableResizing: false,
    enableSorting: false,
    enableHiding: false,
  } as ColumnDef<TData, TValue>;
};
