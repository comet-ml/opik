import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";
import isUndefined from "lodash/isUndefined";
import { TrendingUp, TrendingDown, MoveRight } from "lucide-react";

import { formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import PercentageTrend from "@/components/shared/PercentageTrend/PercentageTrend";
import { Tag } from "@/components/ui/tag";

type CustomMeta = {
  scoreMap: Record<string, { score: number; percentage?: number }>;
  baseScore?: number;
};

const ObjectiveScoreCell = (context: CellContext<unknown, unknown>) => {
  const id = get(context.row.original, "id", "");
  const { custom } = context.column.columnDef.meta ?? {};
  const { scoreMap, baseScore } = (custom ?? {}) as CustomMeta;

  const scoreData = scoreMap[id];
  const score = scoreData?.score;
  const percentage = scoreData?.percentage;

  let trendElement = null;
  let showScore = true;

  if (!isUndefined(percentage)) {
    trendElement = <PercentageTrend percentage={percentage} />;
  } else if (isNumber(baseScore) && isNumber(score)) {
    const diff = score - baseScore;
    showScore = false;

    if (diff !== 0) {
      const Icon = diff > 0 ? TrendingUp : TrendingDown;
      const variant = diff > 0 ? "green" : "red";

      trendElement = (
        <Tag size="md" variant={variant} className="flex-row flex-nowrap gap-1">
          <div className="flex max-w-full items-center justify-between gap-0.5">
            <Icon className="size-3 shrink-0" />
            <div className="min-w-8 text-right">
              {formatNumericData(Math.abs(diff))}
            </div>
          </div>
        </Tag>
      );
    } else {
      trendElement = (
        <Tag size="md" variant="gray" className="flex-row flex-nowrap gap-1">
          <div className="flex max-w-full items-center justify-between gap-0.5">
            <MoveRight className="size-3 shrink-0" />
            <div className="min-w-8 text-right">0</div>
          </div>
        </Tag>
      );
    }
  }

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {showScore && (isNumber(score) ? formatNumericData(score) : "-")}
      {trendElement}
    </CellWrapper>
  );
};

export default ObjectiveScoreCell;
