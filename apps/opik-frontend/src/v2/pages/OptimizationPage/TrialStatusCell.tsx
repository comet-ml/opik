import React from "react";
import { CellContext } from "@tanstack/react-table";

import { cn } from "@/lib/utils";
import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  STATUS_VARIANT_MAP,
  getTrialDotColor,
  type TrialStatus,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const TrialStatusCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  // The status map is computed once on the page (single source shared with the
  // chart and the sidebar's status card) and passed down through column meta.
  const { statusMap, bestCandidateId, isTestSuite } = (custom ?? {}) as {
    statusMap?: Map<string, TrialStatus>;
    bestCandidateId?: string;
    isTestSuite?: boolean;
  };

  const isBest = bestCandidateId === row.candidateId;
  const status = statusMap?.get(row.candidateId) ?? "pruned";
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag
        variant={isBest ? "green" : STATUS_VARIANT_MAP[status]}
        size={tagSize}
        className={cn("flex items-center gap-1.5", !isBest && "capitalize")}
      >
        <span
          className="size-1.5 shrink-0 rounded-full"
          style={{
            backgroundColor: getTrialDotColor({ status, isBest, isTestSuite }),
          }}
        />
        {isBest ? "Best" : status}
      </Tag>
    </CellWrapper>
  );
};

export default TrialStatusCell;
