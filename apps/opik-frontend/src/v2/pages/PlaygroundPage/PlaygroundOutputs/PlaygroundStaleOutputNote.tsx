import React from "react";
import { Info } from "lucide-react";

import { cn } from "@/lib/utils";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const STALE_OUTPUT_EXPLANATION =
  "Prompt changed since the last run. Re-run to update results.";

interface PlaygroundStaleOutputNoteProps {
  compact?: boolean;
  className?: string;
}

const PlaygroundStaleOutputNote: React.FC<PlaygroundStaleOutputNoteProps> = ({
  compact = false,
  className,
}) => (
  <TooltipWrapper content={compact ? STALE_OUTPUT_EXPLANATION : null}>
    <span
      data-testid="playground-stale-output-note"
      className={cn(
        "comet-body-xs flex w-fit cursor-default items-start gap-1 text-muted-slate",
        className,
      )}
    >
      <Info className="mt-0.5 size-3 shrink-0" />
      {compact ? "Prompt changed" : STALE_OUTPUT_EXPLANATION}
    </span>
  </TooltipWrapper>
);

export default PlaygroundStaleOutputNote;
