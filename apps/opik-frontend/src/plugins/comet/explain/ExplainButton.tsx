import { useState } from "react";
import { Sparkles } from "lucide-react";
import { Popover, PopoverContent, PopoverTrigger } from "@/ui/popover";
import { ExplainButtonProps } from "@/types/assistant-sidebar";
import { OpikEvent, trackEvent } from "@/lib/analytics/tracking";
import useExplainStore, { useCanExplain } from "./explainStore";
import { getExplainConfig } from "./registry";
import ExplainPopover from "./ExplainPopover";

const ExplainButton = ({ target }: ExplainButtonProps) => {
  const canExplain = useCanExplain();
  const explain = useExplainStore((s) => s.explain);
  const config = getExplainConfig(target.kind);
  const [open, setOpen] = useState(false);

  // Fail-closed: pod not ready / no "explain" capability / unknown kind → no button.
  if (!canExplain || !config) return null;

  return (
    <Popover
      open={open}
      onOpenChange={(next) => {
        setOpen(next);
        if (next) {
          explain(target);
          trackEvent(OpikEvent.EXPLAIN_CLICKED, { kind: target.kind });
        }
      }}
    >
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label={config.label}
          className="flex size-6 items-center justify-center rounded text-light-slate transition-colors hover:bg-primary-100 hover:text-primary"
        >
          <Sparkles className="size-3.5" />
        </button>
      </PopoverTrigger>
      <PopoverContent
        side="right"
        align="start"
        className="w-80 font-mono text-xs"
        // Keep clicks inside the popover from bubbling to the table row.
        onClick={(e) => e.stopPropagation()}
      >
        <ExplainPopover target={target} onContinue={() => setOpen(false)} />
      </PopoverContent>
    </Popover>
  );
};

export default ExplainButton;
