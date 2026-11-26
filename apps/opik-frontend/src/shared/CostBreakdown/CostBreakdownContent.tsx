import React from "react";
import SpanCostBreakdown from "./SpanCostBreakdown";
import ThreadCostBreakdown from "./ThreadCostBreakdown";
import TraceCostBreakdown from "./TraceCostBreakdown";
import { CostBreakdownTarget } from "./types";

type CostBreakdownContentProps = {
  target: CostBreakdownTarget;
};

const CostBreakdownContent: React.FC<CostBreakdownContentProps> = ({
  target,
}) => {
  if (target.kind === "span") return <SpanCostBreakdown span={target.span} />;
  if (target.kind === "trace") {
    return (
      <TraceCostBreakdown
        projectId={target.projectId}
        traceId={target.traceId}
        cost={target.cost}
        spans={target.spans}
      />
    );
  }
  return (
    <ThreadCostBreakdown
      projectId={target.projectId}
      threadId={target.threadId}
      cost={target.cost}
    />
  );
};

export default CostBreakdownContent;
