import React from "react";
import { HeaderContext } from "@tanstack/react-table";
import { ChevronDown } from "lucide-react";
import useLocalStorageState from "use-local-storage-state";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { TAG_VARIANTS_COLOR_MAP } from "@/components/ui/tag";
import { generateTagVariant } from "@/lib/traces";
import HeaderWrapper from "@/components/shared/DataTableHeaders/HeaderWrapper";
import useSortableHeader from "@/components/shared/DataTableHeaders/useSortableHeader";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { FeedbackScoreCustomMeta } from "@/types/feedback-scores";
import { formatNumericData } from "@/lib/utils";
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
  const { colorMap, feedbackKey, dataFormatter = formatNumericData } =
    (custom ?? {}) as CustomMeta;

  const storageKey = `${FEEDBACK_SCORE_PERCENTILE_STORAGE_KEY_PREFIX}-${feedbackKey}`;
  const [selectedPercentile, setSelectedPercentile] =
    useLocalStorageState<PercentileValue>(storageKey, {
      defaultValue: "p50",
    });

  // Use color from colorMap if available, otherwise fall back to default
  const color =
    feedbackKey && colorMap?.[feedbackKey]
      ? colorMap[feedbackKey]
      : TAG_VARIANTS_COLOR_MAP[generateTagVariant(header ?? "")!];

  const { className, onClickHandler, renderSort } = useSortableHeader({
    column,
  });

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
      className={className}
      onClick={onClickHandler}
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

export default FeedbackScoreMetricsHeader;

