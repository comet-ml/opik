import React from "react";
import { Span } from "@/types/traces";
import ModelCostBreakdown from "./ModelCostBreakdown";
import useTraceCostSpans, { COSTED_SPANS_LIMIT } from "./useTraceCostSpans";

type TraceCostBreakdownProps = {
  projectId: string;
  traceId: string;
  cost?: number;
  spans?: Span[];
};

const TraceCostBreakdown: React.FC<TraceCostBreakdownProps> = ({
  projectId,
  traceId,
  cost,
  spans,
}) => {
  const result = useTraceCostSpans({ projectId, traceId, spans });

  return (
    <ModelCostBreakdown
      spans={result.spans}
      total={cost ?? 0}
      entity="trace"
      isPending={result.isPending}
      isError={result.isError}
      scopeNote={
        result.isTruncated
          ? `Based on the first ${COSTED_SPANS_LIMIT.toLocaleString(
              "en-US",
            )} spans with a cost.`
          : undefined
      }
    />
  );
};

export default TraceCostBreakdown;
