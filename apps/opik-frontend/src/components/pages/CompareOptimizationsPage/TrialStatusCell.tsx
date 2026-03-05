import React from "react";
import { CellContext } from "@tanstack/react-table";

import CellWrapper from "@/components/shared/DataTableCells/CellWrapper";
import { Tag, TagProps } from "@/components/ui/tag";
import { AggregatedCandidate } from "@/types/optimizations";
import { computeCandidateStatuses } from "@/components/pages-shared/experiments/OptimizationProgressChart/optimizationChartUtils";

type TrialStatus = "baseline" | "passed" | "lost";

const STATUS_VARIANT_MAP: Record<TrialStatus, TagProps["variant"]> = {
  baseline: "gray",
  passed: "blue",
  lost: "pink",
};

const TrialStatusCell = (context: CellContext<unknown, unknown>) => {
  const row = context.row.original as AggregatedCandidate;
  const { custom } = context.column.columnDef.meta ?? {};
  const { candidates, isOptimizationFinished, bestCandidateId } = (custom ??
    {}) as {
    candidates: AggregatedCandidate[];
    isOptimizationFinished?: boolean;
    bestCandidateId?: string;
  };

  const isBest = bestCandidateId === row.candidateId;

  const statusMap = computeCandidateStatuses(
    candidates ?? [],
    isOptimizationFinished,
  );
  const status = statusMap.get(row.candidateId) ?? "lost";

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
