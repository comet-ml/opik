import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import PercentageTrend from "@/components/shared/PercentageTrend/PercentageTrend";
import { formatScoreDisplay } from "@/lib/feedback-scores";
import TooltipWrapper from "@/components/shared/TooltipWrapper/TooltipWrapper";

type CustomMeta = {
  scoreMap: Record<string, { score: number; percentage?: number }>;
};

const ObjectiveScoreCell = (context: CellContext<unknown, unknown>) => {
  const id = get(context.row.original, "id", "");
  const { custom } = context.column.columnDef.meta ?? {};
  const { scoreMap } = (custom ?? {}) as CustomMeta;

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
      className="gap-2"
    >
      {isNumber(scoreMap[id]?.score) ? (
        <TooltipWrapper content={String(scoreMap[id]?.score)}>
          <span>{formatScoreDisplay(scoreMap[id]?.score)}</span>
        </TooltipWrapper>
      ) : (
        "-"
      )}
      <PercentageTrend percentage={scoreMap[id]?.percentage} />
    </CellWrapper>
  );
};

export default ObjectiveScoreCell;
