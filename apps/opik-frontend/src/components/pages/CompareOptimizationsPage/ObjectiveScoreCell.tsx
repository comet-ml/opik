import React from "react";
import { CellContext } from "@tanstack/react-table";
import get from "lodash/get";
import isNumber from "lodash/isNumber";

import { formatNumericData } from "@/lib/utils";
import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import PercentageTrend from "@/components/pages/CompareOptimizationsPage/PercentageTrend";

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
      {isNumber(scoreMap[id]?.score)
        ? formatNumericData(scoreMap[id]?.score)
        : "-"}
      <PercentageTrend percentage={scoreMap[id]?.percentage} />
    </CellWrapper>
  );
};

export default ObjectiveScoreCell;
