import React, { useMemo } from "react";
import { Eye, EyeOff } from "lucide-react";

import { Trace } from "@/types/traces";
import { Button } from "@/ui/button";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";
import { useHideSpansPreference } from "@/v2/pages-shared/traces/hiddenSpans";
import { isSpanHiddenByDefault } from "@/v2/pages-shared/traces/spanVisibility";

const TOOLTIP =
  "Helper LLM calls such as conversation-title generation. Hidden by default; still counted in thread cost and usage.";

type InternalTracesToggleProps = {
  traces: Trace[];
};

// Labeled show/hide control for a thread's internal traces. Renders nothing when the thread
// has none. Shares the persisted hide preference with the trace-tree hidden-spans toggle.
const InternalTracesToggle: React.FC<InternalTracesToggleProps> = ({
  traces,
}) => {
  const [hidden, setHidden] = useHideSpansPreference();
  const count = useMemo(
    () => traces.filter(isSpanHiddenByDefault).length,
    [traces],
  );

  if (!count) return null;

  const label = `${hidden ? "Show" : "Hide"} internal calls (${count})`;

  return (
    <TooltipWrapper content={TOOLTIP}>
      <Button
        variant="ghost"
        size="2xs"
        className="text-muted-slate"
        aria-label={label}
        aria-pressed={!hidden}
        onClick={() => setHidden(!hidden)}
      >
        {hidden ? (
          <Eye className="mr-1 size-3" />
        ) : (
          <EyeOff className="mr-1 size-3" />
        )}
        <span>{label}</span>
      </Button>
    </TooltipWrapper>
  );
};

export default InternalTracesToggle;
