import { useMemo } from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/shared/DataTableCells/CellWrapper";
import { Tag } from "@/ui/tag";
import { getCellTagSize, TAG_SIZE_MAP } from "@/constants/shared";
import { AggregatedCandidate } from "@/types/optimizations";
import {
  computeCandidateStatuses,
  STATUS_VARIANT_MAP,
  type InProgressInfo,
} from "@/v1/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

interface TrialStatusCustomMeta {
  candidates: AggregatedCandidate[];
  bestCandidateId?: string;
  isTestSuite?: boolean;
  isInProgress?: boolean;
  inProgressInfo?: InProgressInfo;
}

const TrialStatusCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const {
    candidates,
    bestCandidateId,
    isTestSuite,
    isInProgress,
    inProgressInfo,
  } = (custom ?? {}) as TrialStatusCustomMeta;

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

  const isBest = bestCandidateId === row.candidateId;
  const status = statusMap.get(row.candidateId) ?? "pruned";
  const variant = isBest ? "green" : STATUS_VARIANT_MAP[status];
  const label = isBest ? "Best" : status;
  const tagSize = getCellTagSize(context, TAG_SIZE_MAP);

  return (
    <CellWrapper
      metadata={context.column.columnDef.meta}
      tableMetadata={context.table.options.meta}
    >
      <Tag
        variant={variant}
        size={tagSize}
        className={isBest ? undefined : "capitalize"}
      >
        {label}
      </Tag>
    </CellWrapper>
  );
};

export default TrialStatusCell;
