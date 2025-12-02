import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { ChevronDown } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { AggregatedDuration } from "@/types/shared";

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
  const { storageKey, dataFormatter = String, metricsKey } =
    (custom ?? {}) as PercentileMetricsHeaderMeta;

  const [selectedPercentile, setSelectedPercentile] =
    useLocalStorageState<PercentileValue>(storageKey, {
      defaultValue: "p50",
    });

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column,
  });

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
      className={className}
      onClick={onClickHandler}
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
          <DropdownMenuContent align="start">
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

