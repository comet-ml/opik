import React, { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  computeCandidateStatuses,
  STATUS_VARIANT_MAP,
  type InProgressInfo,
} from "@/v2/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

const TrialStatusCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
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
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      {isBest ? (
        <Tag variant="green" size={tagSize}>
          Best
        </Tag>
      ) : (
        <Tag
          variant={STATUS_VARIANT_MAP[status]}
          size={tagSize}
          className="capitalize"
        >
          {status}
        </Tag>
      )}
    </CellWrapper>
  );
};

export default TrialStatusCell;
