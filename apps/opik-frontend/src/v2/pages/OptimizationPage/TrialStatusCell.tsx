import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { AggregatedCandidate } from "@/types/optimizations";
import TrialStatusPill from "@/v2/pages-shared/optimizations/TrialStatusPill";
import { type TrialStatus } from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const TrialStatusCell = (
  context: CellContext<AggregatedCandidate, unknown>,
) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  // The whole-run status map is computed once on the page and shared here, so
  // every row is a single Map lookup rather than an O(n) recompute per cell.
  const { statusMap, bestCandidateId } = (custom ?? {}) as {
    statusMap?: Map<string, TrialStatus>;
    bestCandidateId?: string;
  };

  const isBest = bestCandidateId === row.candidateId;
  const status = statusMap?.get(row.candidateId) ?? "pruned";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <TrialStatusPill status={status} isBest={isBest} />
    </CellWrapper>
  );
};

export default TrialStatusCell;
