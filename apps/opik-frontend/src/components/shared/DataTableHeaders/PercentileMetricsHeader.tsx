import React, { useCallback } from "react";
import { HeaderContext, SortDirection } from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ChevronDown } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { AggregatedDuration } from "@/types/shared";
import { cn } from "@/lib/utils";

export type PercentileValue = "p50" | "p90" | "p99";

const PERCENTILE_OPTIONS: { label: string; value: PercentileValue }[] = [
  { label: "p50", value: "p50" },
  { label: "p90", value: "p90" },
  { label: "p99", value: "p99" },
];

export type PercentileMetricsHeaderMeta = {
  header?: string;
  metricsKey?: string;
  storageKey: string;
  dataFormatter?: (value: number) => string;
};

const PercentileMetricsHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column, table } = context;
  const { header, custom } = column.columnDef.meta ?? {};
  const {
    storageKey,
    dataFormatter = String,
    metricsKey,
  } = (custom ?? {}) as PercentileMetricsHeaderMeta;

  const columnId = column.id;
  const isSortable = column.getCanSort();

  const [selectedPercentile, setSelectedPercentile] =
    useLocalStorageState<PercentileValue>(storageKey, {
      defaultValue: "p50",
    });

  // Build dynamic sort field based on selected percentile (e.g., "duration.p50")
  const sortField = `${columnId}.${selectedPercentile}`;

  // Get current sorting state for this column's dynamic field
  const sortingState = table.getState().sorting;
  const currentSort = sortingState.find((s) => s.id === sortField);
  const direction: SortDirection | false = currentSort
    ? currentSort.desc
      ? "desc"
      : "asc"
    : false;

  // Custom sort handler that uses the dynamic field
  const handleSort = useCallback(() => {
    if (!isSortable) return;

    const currentSorting = table.getState().sorting;

    // Find if we're currently sorting by this field
    const existingSort = currentSorting.find((s) => s.id === sortField);

    let newSorting;
    if (!existingSort) {
      // Start sorting ASC
      newSorting = [{ id: sortField, desc: false }];
    } else if (!existingSort.desc) {
      // Currently ASC, switch to DESC
      newSorting = [{ id: sortField, desc: true }];
    } else {
      // Currently DESC, remove sorting (cycle back)
      newSorting = [{ id: sortField, desc: false }];
    }

    table.setSorting(newSorting);
  }, [isSortable, table, sortField]);

  // Render sort indicator
  const renderSort = () => {
    if (!isSortable) return null;

    const Icon = direction === "asc" ? ArrowUp : ArrowDown;
    return (
      <Icon
        className={cn(
          "hidden size-3.5 shrink-0 group-hover:inline",
          direction && "inline",
        )}
      />
    );
  };

  // Calculate the aggregated value across all visible rows
  const rows = table.getRowModel().rows;
  const aggregatedValue = React.useMemo(() => {
    if (!metricsKey || rows.length === 0) return null;

    const values: number[] = [];
    rows.forEach((row) => {
      const metrics = get(row.original, metricsKey) as
        | AggregatedDuration
        | undefined;
      if (metrics && isNumber(metrics[selectedPercentile])) {
        values.push(metrics[selectedPercentile]);
      }
    });

    if (values.length === 0) return null;

    // Calculate average of the selected percentile across all rows
    const sum = values.reduce((acc, v) => acc + v, 0);
    return sum / values.length;
  }, [rows, metricsKey, selectedPercentile]);

  return (
    <HeaderWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className={isSortable ? "group cursor-pointer" : undefined}
      onClick={handleSort}
      supportStatistic={false}
    >
      <div className="flex w-full flex-col gap-0.5">
        <div className="flex items-center gap-1">
          <span className="truncate">{header}</span>
          {renderSort()}
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <div
              className="flex max-w-full cursor-pointer items-center"
              onClick={(e) => e.stopPropagation()}
              onPointerDown={(e) => e.stopPropagation()}
            >
              <span className="comet-body-s truncate text-foreground">
                <span>{selectedPercentile}</span>
                {aggregatedValue !== null && (
                  <span className="ml-1 font-semibold">
                    {dataFormatter(aggregatedValue)}
                  </span>
                )}
              </span>
              <ChevronDown className="ml-0.5 size-3.5 shrink-0" />
            </div>
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="start"
            onClick={(e) => e.stopPropagation()}
            onPointerDown={(e) => e.stopPropagation()}
          >
            {PERCENTILE_OPTIONS.map((option) => (
              <DropdownMenuCheckboxItem
                key={option.value}
                onSelect={() => setSelectedPercentile(option.value)}
                checked={selectedPercentile === option.value}
              >
                {option.label}
              </DropdownMenuCheckboxItem>
            ))}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
    </HeaderWrapper>
  );
};

export default PercentileMetricsHeader;
