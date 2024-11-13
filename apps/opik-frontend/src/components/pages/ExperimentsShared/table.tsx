import React from "react";
import { Checkbox } from "@/components/ui/checkbox";
import {
  CellContext,
  ColumnDef,
  flexRender,
  GroupingState,
  Row,
} from "@tanstack/react-table";
import { ColumnData } from "@/types/shared";
import { Button } from "@/components/ui/button";
import { ChevronDown, ChevronUp, Text } from "lucide-react";
import { mapColumnDataFields } from "@/lib/table";
import { cn } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import {
  checkIsMoreRowId,
  GroupedExperiment,
  GROUPING_COLUMN,
} from "@/hooks/useGroupedExperimentsList";
import ResourceLink, {
  RESOURCE_TYPE,
} from "@/components/shared/ResourceLink/ResourceLink";

export const GROUPING_CONFIG = {
  groupedColumnMode: false as const,
  grouping: [GROUPING_COLUMN] as GroupingState,
};

export const getRowId = (e: GroupedExperiment) => e.id;
export const getIsMoreRow = (row: Row<GroupedExperiment>) =>
  checkIsMoreRowId(row?.original?.id || "");

export const generateExperimentNameColumDef = <TData,>({
  size,
  asResource = false,
}: {
  size?: number;
  asResource?: boolean;
}) => {
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
          {asResource ? (
            <div className="ml-6 min-w-1 max-w-full">
              <ResourceLink
                id={data.dataset_id}
                name={data.name}
                resource={RESOURCE_TYPE.experiment}
                search={{
                  experiments: [data.id],
                }}
              />
            </div>
          ) : (
            <span className="ml-6 truncate">
              {context.getValue() as string}
            </span>
          )}
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
              className="ml-3"
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
    size: 0,
    minSize: 0,
    enableResizing: false,
    enableSorting: false,
    enableHiding: false,
  } as ColumnDef<TData, TValue>;
};
