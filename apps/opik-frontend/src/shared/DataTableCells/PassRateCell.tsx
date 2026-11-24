import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { Tag } from "@/ui/tag";
import { formatAsPercentage } from "@/lib/optimization-formatters";
import CustomSquare from "@/icons/custom-square.svg?react";
import StatusDotPill from "@/shared/StatusDotPill/StatusDotPill";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";

export const formatPassRate = (
  passRate: number,
  passedCount: number,
  totalCount: number,
): string => {
  return `${(passRate * 100).toFixed(1)}% (${passedCount}/${totalCount})`;
};

// The backend sends exactly 1 only when every item passed; anything below
// rounds no higher than 99% so the label never reads "100%" in red.
export const formatPassRatePercent = (passRate: number) =>
  formatAsPercentage(passRate < 1 ? Math.min(passRate, 0.99) : passRate);

const PASS_RATE_DOT_COLOR = {
  passed: "var(--color-green-bright)",
  failed: "var(--color-red)",
};

const PassRateCell = <TData,>(context: CellContext<TData, unknown>) => {
  const row = context.row.original as Record<string, unknown>;
  const passRate = get(row, "pass_rate") as number | undefined;
  const passedCount = get(row, "passed_count") as number | undefined;
  const totalCount = get(row, "total_count") as number | undefined;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

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
        // Same filled status tag as the Alerts and Online evaluation status
        // columns.
        <TooltipWrapper content={tooltip}>
          <Tag variant={passRate === 1 ? "green" : "red"} size={tagSize}>
            <div className="flex items-center gap-1">
              <CustomSquare className="size-3 shrink-0" />
              <span className="truncate">
                {formatPassRatePercent(passRate)}
              </span>
            </div>
          </Tag>
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
        // Group rows use the lighter status-dot pill, so the filled tags on the
        // experiment rows inside the group stay the primary signal.
        <TooltipWrapper content={tooltip}>
          <StatusDotPill
            dotColor={
              passRate === 1
                ? PASS_RATE_DOT_COLOR.passed
                : PASS_RATE_DOT_COLOR.failed
            }
          >
            {formatPassRatePercent(passRate)}
          </StatusDotPill>
        </TooltipWrapper>
      )}
    </CellWrapper>
  );
};

PassRateCell.Aggregation = PassRateAggregationCell;

export default PassRateCell;
