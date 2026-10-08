import React from "react";
import { Info } from "lucide-react";

import { cn } from "@/lib/utils";
import { PlaygroundRunInputChange } from "@/types/playground";
import TooltipWrapper from "@/shared/TooltipWrapper/TooltipWrapper";

const CHANGE_ORDER: PlaygroundRunInputChange[] = [
  "prompt",
  "model",
  "parameters",
];

// Outputs saved before the store tracked what changed have no list.
const UNKNOWN_CHANGES: PlaygroundRunInputChange[] = ["prompt"];

export const describeStaleChanges = (changes?: PlaygroundRunInputChange[]) => {
  const ordered = CHANGE_ORDER.filter((change) =>
    (changes?.length ? changes : UNKNOWN_CHANGES).includes(change),
  );
  const listed =
    ordered.length > 1
      ? `${ordered.slice(0, -1).join(", ")} and ${ordered[ordered.length - 1]}`
      : ordered[0];

  return `${listed.charAt(0).toUpperCase()}${listed.slice(1)} changed`;
};

interface PlaygroundStaleOutputNoteProps {
  changes?: PlaygroundRunInputChange[];
  compact?: boolean;
  className?: string;
}

const PlaygroundStaleOutputNote: React.FC<PlaygroundStaleOutputNoteProps> = ({
  changes,
  compact = false,
  className,
}) => {
  const summary = describeStaleChanges(changes);
  const explanation = `${summary} since the last run. Re-run to update results.`;

  return (
    <TooltipWrapper content={compact ? explanation : null}>
      <span
        data-testid="playground-stale-output-note"
        className={cn(
          "comet-body-xs flex w-fit cursor-default items-start gap-1 text-muted-slate",
          className,
        )}
      >
        <Info className="mt-0.5 size-3 shrink-0" />
        {compact ? summary : explanation}
      </span>
    </TooltipWrapper>
  );
};

export default PlaygroundStaleOutputNote;
