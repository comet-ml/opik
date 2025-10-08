import React, { useMemo, useState, useEffect } from "react";
import {
  ColumnDef,
  ExpandedState,
  flexRender,
  getCoreRowModel,
  getExpandedRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { ChevronDown, ChevronRight } from "lucide-react";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";
import isString from "lodash/isString";
import isNumber from "lodash/isNumber";
import isBoolean from "lodash/isBoolean";
import isNull from "lodash/isNull";

import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";

interface JsonKeyValueTableProps {
  data: unknown;
  className?: string;
  maxDepth?: number;
}

interface JsonRowData {
  key: string;
  value: unknown;
  depth: number;
  isExpandable: boolean;
  children?: JsonRowData[];
}

/**
 * Helper function to filter out null values from object entries
 */
const filterNullObjectEntries = (
  entries: [string, unknown][],
): [string, unknown][] => {
  return entries.filter(([, value]) => !isNull(value));
};

/**
 * Helper function to filter out null values from arrays
 */
const filterNullArrayItems = (items: unknown[]): unknown[] => {
  return items.filter((item) => !isNull(item));
};

const JsonValue: React.FC<{
  value: unknown;
  maxDepth: number;
  depth: number;
}> = ({ value, maxDepth, depth }) => {
  if (depth >= maxDepth) {
    return (
      <span className="comet-code text-muted-foreground">
        {isObject(value) ? "{...}" : String(value)}
      </span>
    );
  }

  if (isNull(value)) {
    return <span className="comet-code text-muted-foreground">null</span>;
  }

  if (isBoolean(value)) {
    return <span className="comet-code text-foreground">{String(value)}</span>;
  }

  if (isNumber(value)) {
    return <span className="comet-code text-foreground">{value}</span>;
  }

  if (isString(value)) {
    return (
      <span className="comet-code text-foreground">&quot;{value}&quot;</span>
    );
  }

  if (isArray(value)) {
    if (value.length === 0) {
      return (
        <span className="comet-code italic text-muted-foreground">
          empty list
        </span>
      );
    }
    return (
      <span className="comet-code italic text-muted-foreground">
        {value.length} item{value.length !== 1 ? "s" : ""}
      </span>
    );
  }

  if (isObject(value)) {
    const entries = Object.entries(value);
    if (entries.length === 0) {
      return (
        <span className="comet-code italic text-muted-foreground">
          empty object
        </span>
      );
    }
    return (
      <span className="comet-code italic text-muted-foreground">
        {entries.length} propert{entries.length !== 1 ? "ies" : "y"}
      </span>
    );
  }

  return <span className="comet-code">{String(value)}</span>;
};

const JsonKeyValueTable: React.FC<JsonKeyValueTableProps> = ({
  data,
  className,
  maxDepth = 5,
}) => {
  // Initialize with all items expanded - use a simple approach
  const [expanded, setExpanded] = useState<ExpandedState>({});

  const tableData = useMemo(() => {
    if (!isObject(data) || isNull(data)) {
      return [];
    }

    const entries = Object.entries(data);
    return filterNullObjectEntries(entries).map(([key, value]) => ({
      key,
      value,
      depth: 0,
      isExpandable: isObject(value) && !isNull(value) && 0 < maxDepth,
    }));
  }, [data, maxDepth]);

  const columns: ColumnDef<JsonRowData>[] = useMemo(
    () => [
      {
        id: "key",
        header: "Key",
        cell: ({ row }) => (
          <div className="flex items-center gap-2">
            {row.original.isExpandable && (
              <Button
                variant="ghost"
                size="icon-3xs"
                className="size-4 p-0"
                onClick={() => row.toggleExpanded()}
              >
                {row.getIsExpanded() ? (
                  <ChevronDown className="size-3" />
                ) : (
                  <ChevronRight className="size-3" />
                )}
              </Button>
            )}
            <span className="comet-code font-medium text-foreground">
              {row.original.key}
            </span>
          </div>
        ),
      },
      {
        id: "value",
        header: "Value",
        cell: ({ row }) => (
          <JsonValue
            value={row.original.value}
            maxDepth={maxDepth}
            depth={row.original.depth}
          />
        ),
      },
    ],
    [maxDepth],
  );

  const table = useReactTable({
    data: tableData,
    columns,
    state: {
      expanded,
    },
    onExpandedChange: setExpanded,
    getCoreRowModel: getCoreRowModel(),
    getExpandedRowModel: getExpandedRowModel(),
    getSubRows: (row) => {
      if (!row.isExpandable || row.depth >= maxDepth) {
        return undefined;
      }

      const value = row.value;
      if (isArray(value)) {
        return filterNullArrayItems(value).map((item, index) => ({
          key: `Item ${index + 1}`,
          value: item,
          depth: row.depth + 1,
          isExpandable:
            isObject(item) && !isNull(item) && row.depth + 1 < maxDepth,
        }));
      }

      if (isObject(value)) {
        return filterNullObjectEntries(Object.entries(value)).map(
          ([key, val]) => ({
            key,
            value: val,
            depth: row.depth + 1,
            isExpandable:
              isObject(val) && !isNull(val) && row.depth + 1 < maxDepth,
          }),
        );
      }

      return undefined;
    },
  });

  // Expand all items after table is created
  useEffect(() => {
    const expandAll = () => {
      const allExpanded: ExpandedState = {};

      // Get all rows from the table and expand them
      const rows = table.getRowModel().rows;
      rows.forEach((row, index) => {
        if (row.original.isExpandable) {
          allExpanded[index.toString()] = true;
        }
      });

      setExpanded(allExpanded);
    };

    // Use a small delay to ensure table is fully rendered
    const timer = setTimeout(expandAll, 0);
    return () => clearTimeout(timer);
  }, [tableData, table]);

  if (tableData.length === 0) {
    return (
      <div className={cn("comet-code text-muted-foreground p-2", className)}>
        {isNull(data) ? "null" : "{}"}
      </div>
    );
  }

  return (
    <div
      className={cn(
        "bg-muted/30 rounded-md overflow-hidden font-mono",
        className,
      )}
    >
      <Table>
        <TableHeader>
          {table.getHeaderGroups().map((headerGroup) => (
            <TableRow key={headerGroup.id} className="border-b">
              {headerGroup.headers.map((header) => (
                <TableHead
                  key={header.id}
                  className="w-1/2 px-4 py-2 font-mono font-medium text-muted-foreground"
                >
                  {header.isPlaceholder
                    ? null
                    : flexRender(
                        header.column.columnDef.header,
                        header.getContext(),
                      )}
                </TableHead>
              ))}
            </TableRow>
          ))}
        </TableHeader>
        <TableBody>
          {table.getRowModel().rows.map((row) => (
            <TableRow key={row.id}>
              {row.getVisibleCells().map((cell, index) => (
                <TableCell
                  key={cell.id}
                  className="px-4 py-2"
                  style={{
                    paddingLeft:
                      index === 0
                        ? `${row.original.depth * 24 + 16}px`
                        : "16px",
                  }}
                >
                  {flexRender(cell.column.columnDef.cell, cell.getContext())}
                </TableCell>
              ))}
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  );
};

export default JsonKeyValueTable;
