import React, { useMemo } from "react";

import { Span, Trace } from "@/types/traces";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { isObjectSpan } from "@/lib/traces";
import { useHideSpansPreference } from "@/v2/pages-shared/traces/hiddenSpans";
import { isSpanHiddenByDefault } from "@/v2/pages-shared/traces/spanVisibility";

type VisibleSpanCountProps = {
  // Total number of spans in the trace (hidden ones included).
  total: number;
  // The span list, used to count how many are currently collapsed.
  spans: Array<Span | Trace>;
};

const LABEL_CLASS = "comet-body-xs-accented whitespace-nowrap text-foreground";

// Shows the number of spans currently displayed in the tree, with the total on hover when
// some are collapsed. Self-contained: reads the shared collapse preference itself.
const VisibleSpanCount: React.FC<VisibleSpanCountProps> = ({
  total,
  spans,
}) => {
  const [hidden] = useHideSpansPreference();
  // Count hidden over spans only — `total` excludes the trace root, so counting it here
  // (treeData includes the trace) would undercount `displayed` if a trace were ever hidden.
  const hiddenCount = useMemo(
    () => spans.filter((s) => isObjectSpan(s) && isSpanHiddenByDefault(s)).length,
    [spans],
  );

  const displayed = hidden ? total - hiddenCount : total;

  if (displayed < total) {
    return (
      <TooltipWrapper content={`${total} spans in total`}>
        <span className={LABEL_CLASS}>Spans ({displayed})</span>
      </TooltipWrapper>
    );
  }

  return <span className={LABEL_CLASS}>Spans ({total})</span>;
};

export default VisibleSpanCount;
