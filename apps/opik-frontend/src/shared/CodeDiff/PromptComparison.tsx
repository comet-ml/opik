import React, { useMemo, useState } from "react";
import { ChevronDown, GitCompare } from "lucide-react";

import GitCompareOff from "@/icons/git-compare-off.svg?react";
import { cn } from "@/lib/utils";
import TextDiff from "./TextDiff";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { PromptComparisonTarget } from "./promptComparisonTargets";
import {
  buildDiffRows,
  buildPromptRows,
  getRoleLabel,
} from "./promptMessageDiff";

type PromptComparisonProps = {
  /** The prompt being inspected — the "current" side of the diff. */
  current: unknown;
  /** Label for the current side, shown after the arrow (e.g. "Trial"). */
  currentLabel?: string;
  /**
   * Comparison targets (baseline first, then parents), typically built via
   * {@link ./promptComparisonTargets#buildPromptComparisonTargets}. With no
   * targets the current prompt is shown on its own (no diff).
   */
  targets: PromptComparisonTarget[];
  /** Target selected initially; falls back to the first target. */
  defaultTargetId?: string;
  /** Show the built-in target picker + diff toggle bar. Default true. */
  showControls?: boolean;
  /**
   * Section title shown on the left of the control bar while NOT diffing (e.g.
   * "Trial prompt"). Once diffing starts it is replaced by the target picker.
   * When omitted, the target picker is always shown (the original behavior).
   */
  title?: string;
  /** Whether the built-in toggle starts in the diff state. Default true. */
  defaultDiff?: boolean;
  /**
   * Controlled diff state. When provided, overrides the internal toggle — use
   * together with `showControls={false}` to drive the diff from an outer header.
   */
  diff?: boolean;
  className?: string;
};

/**
 * Prompt diff: unchanged lines stay clean, and within a changed line only the
 * words that actually differ are highlighted (added green, removed struck-through
 * red). Delegates to the shared word-level {@link ./TextDiff} so an unchanged
 * leading sentence in an otherwise-edited paragraph is no longer painted as both
 * removed and added.
 */
const LineDiff: React.FC<{ base: string; current: string }> = ({
  base,
  current,
}) => (
  <div className="comet-body-s break-words text-foreground">
    <TextDiff content1={base} content2={current} mode="words" />
  </div>
);

const PlainText: React.FC<{ text: string }> = ({ text }) => (
  <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
    {text}
  </div>
);

/** One per-role section: a labelled card wrapping either plain text or a diff. */
const RoleCard: React.FC<{ role: string; children: React.ReactNode }> = ({
  role,
  children,
}) => (
  <div className="flex w-full flex-col rounded-md border bg-primary-foreground px-3 py-2">
    <div className="pb-1.5 pt-0.5">
      <div className="comet-body-xs-accented capitalize text-muted-slate">
        {getRoleLabel(role)}
      </div>
    </div>
    {children}
  </div>
);

/**
 * "[target] → [current]" picker: a dropdown to choose what the current prompt
 * is diffed against, followed by the current side's label.
 */
const TargetPicker: React.FC<{
  targets: PromptComparisonTarget[];
  selected: PromptComparisonTarget;
  currentLabel: string;
  onSelect: (id: string) => void;
}> = ({ targets, selected, currentLabel, onSelect }) => (
  <>
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button
          type="button"
          className="comet-body-s-accented inline-flex items-center gap-0.5 border-b border-foreground pb-px text-foreground outline-none"
        >
          {/* Label + caption share a baseline so "Parent" (14px) and its
              "Trial #N" caption (12px) sit on the same line rather than
              centre-aligning to different heights, with a small gap between
              them (design QA round 2). */}
          <span className="inline-flex items-baseline gap-1.5">
            {selected.label}
            {selected.caption && (
              <span className="comet-body-xs text-muted-slate">
                {selected.caption}
              </span>
            )}
          </span>
          <ChevronDown className="size-3.5" />
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-[180px]">
        <DropdownMenuLabel size="sm">Compare against</DropdownMenuLabel>
        <DropdownMenuSeparator className="bg-border" />
        {targets.map((target) => (
          <DropdownMenuItem
            key={target.id}
            size="sm"
            selected={target.id === selected.id}
            onSelect={() => onSelect(target.id)}
            className="justify-between gap-2"
          >
            <span>{target.label}</span>
            {target.caption && (
              <span className="comet-body-xs text-muted-slate">
                {target.caption}
              </span>
            )}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
    <span className="comet-body-s-accented text-foreground">
      → {currentLabel}
    </span>
  </>
);

/** Toggle between the diff view and the plain current-prompt view. */
const DiffToggle: React.FC<{ showDiff: boolean; onToggle: () => void }> = ({
  showDiff,
  onToggle,
}) => (
  <button
    type="button"
    onClick={onToggle}
    className="comet-body-s inline-flex items-center gap-1 text-foreground"
  >
    {showDiff ? (
      <GitCompareOff className="size-3.5" />
    ) : (
      <GitCompare className="size-3.5" />
    )}
    {showDiff ? "Hide diff" : "Show diff"}
  </button>
);

/**
 * Shared prompt-diff surface for the Optimization Runs screens (trial sidebar,
 * overview best-prompt panel, trials prompt cell). Renders a "[target] → [current]"
 * control with a Baseline/Parent picker, a Hide/Show diff toggle, and the prompt
 * grouped into per-role cards — diffed line by line against the chosen target.
 */
const PromptComparison: React.FunctionComponent<PromptComparisonProps> = ({
  current,
  currentLabel = "Trial",
  targets,
  defaultTargetId,
  showControls = true,
  title,
  defaultDiff = true,
  diff,
  className,
}) => {
  const initialId =
    defaultTargetId && targets.some((t) => t.id === defaultTargetId)
      ? defaultTargetId
      : targets[0]?.id;

  const [selectedId, setSelectedId] = useState<string | undefined>(initialId);
  const [internalShowDiff, setShowDiff] = useState(defaultDiff);
  const showDiff = diff ?? internalShowDiff;

  // Always derive from the live targets so a stale selection can't point at a
  // target that no longer exists.
  const selectedTarget = useMemo(
    () => targets.find((t) => t.id === selectedId) ?? targets[0],
    [targets, selectedId],
  );

  const hasTargets = targets.length > 0;
  const diffActive = hasTargets && showDiff && Boolean(selectedTarget);

  const cards = useMemo(() => {
    if (diffActive && selectedTarget) {
      return buildDiffRows(selectedTarget.prompt, current).map((row) => (
        <RoleCard key={row.role} role={row.role}>
          <LineDiff base={row.baseContent} current={row.currentContent} />
        </RoleCard>
      ));
    }

    return buildPromptRows(current).map((row) => (
      <RoleCard key={row.role} role={row.role}>
        <PlainText text={row.content} />
      </RoleCard>
    ));
  }, [diffActive, selectedTarget, current]);

  if (cards.length === 0) return null;

  // A plain title stands in for the target picker until diffing begins; without
  // a title the picker always shows (the original behavior).
  const showTitle = Boolean(title) && !diffActive;
  const showControlBar = showControls && (hasTargets || showTitle);

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      {showControlBar && (
        <div className="flex items-center justify-between pl-1">
          <div className="flex items-center gap-1.5">
            {showTitle ? (
              // Mirror the picker's box (border-b + pb-px, here transparent) so
              // the control bar keeps the same height when toggling into the
              // diff view — otherwise the prompt cards jump down a couple px.
              <span className="comet-body-s-accented inline-flex items-center border-b border-transparent pb-px text-foreground">
                {title}
              </span>
            ) : (
              selectedTarget && (
                <TargetPicker
                  targets={targets}
                  selected={selectedTarget}
                  currentLabel={currentLabel}
                  onSelect={setSelectedId}
                />
              )
            )}
          </div>
          {hasTargets && (
            <DiffToggle
              showDiff={showDiff}
              onToggle={() => setShowDiff((prev) => !prev)}
            />
          )}
        </div>
      )}
      <div className="flex flex-col gap-1.5">{cards}</div>
    </div>
  );
};

export default PromptComparison;
