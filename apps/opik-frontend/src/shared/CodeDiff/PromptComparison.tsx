import React, { useMemo, useState } from "react";

import { cn } from "@/lib/utils";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/ui/select";
import PromptDiff from "./PromptDiff";
import { PromptComparisonTarget } from "./promptComparisonTargets";

type PromptComparisonProps = {
  /** The prompt being inspected (rendered as the "current" side of the diff). */
  current: unknown;
  /**
   * Comparison targets, typically built via
   * {@link ./promptComparison#buildPromptComparisonTargets}. Baseline first,
   * then parents. Renders nothing when empty.
   */
  targets: PromptComparisonTarget[];
  /** Target selected initially; falls back to the first target. */
  defaultTargetId?: string;
  className?: string;
};

/**
 * Shared prompt-diff surface with a "Compare against: Baseline / Parent"
 * control. Wraps {@link PromptDiff} and owns the target selection so the three
 * optimization surfaces (overview best-prompt panel, trials prompt cell, trial
 * sidebar) can drop it in without re-implementing the picker or the diff.
 */
const PromptComparison: React.FunctionComponent<PromptComparisonProps> = ({
  current,
  targets,
  defaultTargetId,
  className,
}) => {
  const fallbackId = targets[0]?.id;
  const initialId =
    defaultTargetId && targets.some((t) => t.id === defaultTargetId)
      ? defaultTargetId
      : fallbackId;

  const [selectedId, setSelectedId] = useState<string | undefined>(initialId);

  // Always derive from the live targets so a removed/stale selection can't
  // point at a target that no longer exists.
  const selectedTarget = useMemo(
    () => targets.find((t) => t.id === selectedId) ?? targets[0],
    [targets, selectedId],
  );

  if (!selectedTarget) {
    return null;
  }

  const showSelector = targets.length > 1;

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      <div className="flex items-center gap-2">
        <span className="comet-body-s shrink-0 text-muted-slate">
          Compare against:
        </span>
        {showSelector ? (
          <Select value={selectedTarget.id} onValueChange={setSelectedId}>
            <SelectTrigger className="h-7 w-auto gap-1 px-2">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {targets.map((target) => (
                <SelectItem key={target.id} value={target.id}>
                  {target.label}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        ) : (
          <span className="comet-body-s-accented">{selectedTarget.label}</span>
        )}
      </div>
      <PromptDiff baseline={selectedTarget.prompt} current={current} />
    </div>
  );
};

export default PromptComparison;
