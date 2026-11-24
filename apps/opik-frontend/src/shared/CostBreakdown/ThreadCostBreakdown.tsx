import React from "react";
import ModelCostBreakdown from "./ModelCostBreakdown";
import useThreadCostSpans from "./useThreadCostSpans";
import { COSTED_SPANS_LIMIT } from "./useTraceCostSpans";

type ThreadCostBreakdownProps = {
  projectId: string;
  threadId: string;
  cost?: number;
};

const ThreadCostBreakdown: React.FC<ThreadCostBreakdownProps> = ({
  projectId,
  threadId,
  cost,
}) => {
  const result = useThreadCostSpans({ projectId, threadId });
  const scopeNote = [
    result.tracesTotal > result.tracesShown &&
      `Based on the first ${result.tracesShown} of ${result.tracesTotal} traces with a cost.`,
    result.isTruncated &&
      `Traces with more than ${COSTED_SPANS_LIMIT.toLocaleString(
        "en-US",
      )} spans with a cost count only their first ${COSTED_SPANS_LIMIT.toLocaleString(
        "en-US",
      )}.`,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <ModelCostBreakdown
      spans={result.spans}
      total={cost ?? 0}
      entity="thread"
      isPending={result.isPending}
      isError={result.isError}
      scopeNote={scopeNote || undefined}
    />
  );
};

export default ThreadCostBreakdown;
