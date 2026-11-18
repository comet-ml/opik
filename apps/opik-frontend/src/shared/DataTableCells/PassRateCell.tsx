import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { cn } from "@/lib/utils";

export const formatPassRate = (
  passRate: number,
  passedCount: number,
  totalCount: number,
): string => {
  return `${(passRate * 100).toFixed(1)}% (${passedCount}/${totalCount})`;
};

type PassRatePillProps = React.HTMLAttributes<HTMLDivElement> & {
  passRate: number;
  bold?: boolean;
};

// Group rows use the filled ("bold") pill so a group's total stands out from
// the neutral pills of the experiments inside it. The ref and props are
// forwarded because TooltipWrapper's trigger attaches through its child.
const PassRatePill = React.forwardRef<HTMLDivElement, PassRatePillProps>(
  ({ passRate, bold = false, className, ...props }, ref) => {
    const passed = passRate === 1;

    return (
      <div
        ref={ref}
        {...props}
        className={cn(
          "inline-flex h-5 items-center gap-1 rounded-md border px-1.5",
          !bold &&
            "border-[var(--pill-neutral-border)] bg-[var(--pill-neutral-bg)]",
          bold &&
            passed &&
            "border-transparent bg-[var(--tag-green-bg)] text-[var(--tag-green-text)]",
          bold &&
            !passed &&
            "border-transparent bg-[var(--tag-red-bg)] text-[var(--tag-red-text)]",
          className,
        )}
      >
        <span className="flex size-3 shrink-0 items-center justify-center">
          <span
            className={cn(
              "size-1.5 rounded-full",
              passed
                ? "bg-[var(--color-green-bright)]"
                : "bg-[var(--color-red)]",
            )}
          />
        </span>
        <span
          className={cn("comet-body-xs-accented", !bold && "text-foreground")}
        >
          {`${Math.round(passRate * 100)}%`}
        </span>
      </div>
    );
  },
);
PassRatePill.displayName = "PassRatePill";

const PassRateCell = <TData,>(context: CellContext<TData, unknown>) => {
  const row = context.row.original as Record<string, unknown>;
  const passRate = get(row, "pass_rate") as number | undefined;
  const passedCount = get(row, "passed_count") as number | undefined;
  const totalCount = get(row, "total_count") as number | undefined;

  const hasData =
    isNumber(passRate) && isNumber(passedCount) && isNumber(totalCount);
  const tooltip = hasData
    ? formatPassRate(passRate, passedCount, totalCount)
    : undefined;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {hasData ? (
        <TooltipWrapper content={tooltip}>
          <PassRatePill passRate={passRate} />
        </TooltipWrapper>
      ) : (
        <span className="truncate">-</span>
      )}
    </CellWrapper>
  );
};

type CustomMeta = {
  aggregationKey?: string;
};

const PassRateAggregationCell = <TData,>(
  context: CellContext<TData, unknown>,
) => {
  const { custom } = context.column.columnDef.meta ?? {};
  const { aggregationKey } = (custom ?? {}) as CustomMeta;

  const rowId = context.row.id;
  const { aggregationMap } = context.table.options.meta ?? {};

  const data = aggregationMap?.[rowId] ?? {};
  const passRate = get(data, aggregationKey ?? "", undefined) as
    | number
    | undefined;
  const passedCount = get(data, "passed_count_sum", undefined) as
    | number
    | undefined;
  const totalCount = get(data, "total_count_sum", undefined) as
    | number
    | undefined;

  // The backend's group pass rate is the mean of the experiments' pass rates,
  // not passed / total, so the tooltip must not present the counts as its source.
  const tooltip = (
    <div>
      <div>Average of the experiments&apos; pass rates</div>
      {isNumber(passedCount) && isNumber(totalCount) && (
        <div>{`${passedCount} / ${totalCount} items passed in total`}</div>
      )}
    </div>
  );

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isNumber(passRate) && (
        <TooltipWrapper content={tooltip}>
          <PassRatePill passRate={passRate} bold />
        </TooltipWrapper>
      )}
    </CellWrapper>
  );
};

PassRateCell.Aggregation = PassRateAggregationCell;

export default PassRateCell;
