import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Tag } from "@/components/ui/tag";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  computeCandidateStatuses,
  STATUS_VARIANT_MAP,
  type InProgressInfo,
} from "@/components/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const TrialStatusCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    candidates,
    bestCandidateId,
    isEvaluationSuite,
    isInProgress,
    inProgressInfo,
  } = (custom ?? {}) as {
    candidates: AggregatedCandidate[];
    bestCandidateId?: string;
    isEvaluationSuite?: boolean;
    isInProgress?: boolean;
    inProgressInfo?: InProgressInfo;
  };

  const isBest = bestCandidateId === row.candidateId;

  const statusMap = useMemo(
    () =>
      computeCandidateStatuses(
        candidates ?? [],
        isEvaluationSuite,
        isInProgress,
        inProgressInfo,
      ),
    [candidates, isEvaluationSuite, isInProgress, inProgressInfo],
  );
  const status = statusMap.get(row.candidateId) ?? "pruned";

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isBest ? (
        <Tag variant="green" size="md">
          Best
        </Tag>
      ) : (
        <Tag
          variant={STATUS_VARIANT_MAP[status]}
          size="md"
          className="capitalize"
        >
          {status}
        </Tag>
      )}
    </CellWrapper>
  );
};

export default TrialStatusCell;
