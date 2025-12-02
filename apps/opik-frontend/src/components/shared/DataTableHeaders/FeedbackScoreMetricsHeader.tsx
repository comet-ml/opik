import React, { useCallback } from "react";
import { HeaderContext, SortDirection } from "@tanstack/react-table";
import { ArrowDown, ArrowUp, ChevronDown } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import isNumber from "lodash/isNumber";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { FeedbackScoreCustomMeta } from "@/types/feedback-scores";
import { formatNumericData, cn } from "@/lib/utils";
import { FeedbackScoreMetric } from "@/types/datasets";

export type PercentileValue = "p50" | "p90" | "p99";

const PERCENTILE_OPTIONS: { label: string; value: PercentileValue }[] = [
  { label: "p50", value: "p50" },
  { label: "p90", value: "p90" },
  { label: "p99", value: "p99" },
];

export const FEEDBACK_SCORE_PERCENTILE_STORAGE_KEY_PREFIX =
  "experiments-feedback-score-percentile";

type CustomMeta = FeedbackScoreCustomMeta & {
  feedbackKey?: string;
  dataFormatter?: (value: number) => string;
};

const FeedbackScoreMetricsHeader = <TData,>(
  context: HeaderContext<TData, unknown>,
) => {
  const { column, table } = context;
  const { header, custom } = column.columnDef.meta ?? {};
  const {
    colorMap,
    feedbackKey,
    dataFormatter = formatNumericData,
  } = (custom ?? {}) as CustomMeta;

  const columnId = column.id;
  const isSortable = column.getCanSort();

  const storageKey = `${FEEDBACK_SCORE_PERCENTILE_STORAGE_KEY_PREFIX}-${feedbackKey}`;
  const [selectedPercentile, setSelectedPercentile] =
    useLocalStorageState<PercentileValue>(storageKey, {
      defaultValue: "p50",
    });

  // Use the column ID for sorting (e.g., "feedback_scores.Correctness")
  // Backend doesn't support percentile-based sorting for feedback scores yet
  const sortField = columnId;

  // Get current sorting state for this column
  const sortingState = table.getState().sorting;
  const currentSort = sortingState.find((s) => s.id === sortField);
  const direction: SortDirection | false = currentSort
    ? currentSort.desc
      ? "desc"
      : "asc"
    : false;

  // Custom sort handler
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

  // Use color from colorMap if available, otherwise fall back to default
  const color =
    feedbackKey && colorMap?.[feedbackKey]
      ? colorMap[feedbackKey]
      : TAG_VARIANTS_COLOR_MAP[generateTagVariant(header ?? "")!];

  // Calculate aggregated value across visible rows
  const rows = table.getRowModel().rows;
  const aggregatedValue = React.useMemo(() => {
    if (!feedbackKey || rows.length === 0) return null;

    const values: number[] = [];
    rows.forEach((row) => {
      const metrics = (
        row.original as { feedback_score_metrics?: FeedbackScoreMetric[] }
      ).feedback_score_metrics;
      const metric = metrics?.find((m) => m.name === feedbackKey);
      if (metric && isNumber(metric.values[selectedPercentile])) {
        values.push(metric.values[selectedPercentile]);
      }
    });

    if (values.length === 0) return null;

    const sum = values.reduce((acc, v) => acc + v, 0);
    return sum / values.length;
  }, [rows, feedbackKey, selectedPercentile]);

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
          <div
            className="mr-0.5 size-2 shrink-0 rounded-[2px] bg-[--color-bg]"
            style={{ "--color-bg": color } as React.CSSProperties}
          ></div>
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

export default FeedbackScoreMetricsHeader;
