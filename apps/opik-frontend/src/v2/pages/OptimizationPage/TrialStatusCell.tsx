import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { AggregatedCandidate } from "@/types/optimizations";
import TrialStatusPill from "@/v2/pages-shared/optimizations/TrialStatusPill";
import {
  computeCandidateStatuses,
  type InProgressInfo,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const TrialStatusCell = (
  context: CellContext<AggregatedCandidate, unknown>,
) => {
  const row = context.row.original;
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    candidates,
    bestCandidateId,
    isTestSuite,
    isInProgress,
    inProgressInfo,
  } = (custom ?? {}) as {
    candidates: AggregatedCandidate[];
    bestCandidateId?: string;
    isTestSuite?: boolean;
    isInProgress?: boolean;
    inProgressInfo?: InProgressInfo;
  };

  const isBest = bestCandidateId === row.candidateId;

  const statusMap = useMemo(
    () =>
      computeCandidateStatuses(
        candidates ?? [],
        isTestSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isTestSuite, isInProgress, inProgressInfo],
  );
  const status = statusMap.get(row.candidateId) ?? "pruned";

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
