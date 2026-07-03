import React, { useMemo, useState } from "react";
import { diffLines } from "diff";
import { ChevronDown, GitCompare } from "lucide-react";

import GitCompareOff from "@/icons/git-compare-off.svg?react";
import { cn } from "@/lib/utils";
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
  buildRoleDiffRows,
  getRoleLabel,
  groupMessageContentByRole,
  promptToText,
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
 * Line-level diff: unchanged lines keep the full foreground color, added lines
 * get a green band and removed lines a struck-through red band (no word-level
 * inline highlighting, no tag pills).
 */
const LineDiff: React.FC<{ base: string; current: string }> = ({
  base,
  current,
}) => {
  const parts = useMemo(() => diffLines(base, current), [base, current]);

  return (
    <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
      {parts.map((part, index) => (
        <div
          key={index}
          className={cn({
            "bg-diff-added-bg text-diff-added-text": part.added,
            "bg-diff-removed-bg text-diff-removed-text line-through":
              part.removed,
          })}
        >
          {part.value.replace(/\n$/, "")}
        </div>
      ))}
    </div>
  );
};

const RoleCard: React.FC<{ role: string; children: React.ReactNode }> = ({
  role,
  children,
}) => (
  <div className="flex w-full flex-col rounded-md border bg-primary-foreground px-3 py-2">
    <div className="pb-1.5 pt-0.5">
      <span className="comet-body-xs-accented capitalize text-muted-slate">
        {getRoleLabel(role)}
      </span>
    </div>
    {children}
  </div>
);

const PlainText: React.FC<{ text: string }> = ({ text }) => (
  <div className="comet-body-s whitespace-pre-wrap break-words text-foreground">
    {text}
  </div>
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
  const fallbackId = targets[0]?.id;
  const initialId =
    defaultTargetId && targets.some((t) => t.id === defaultTargetId)
      ? defaultTargetId
      : fallbackId;

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

  const body = useMemo(() => {
    if (diffActive && selectedTarget) {
      const rows = buildRoleDiffRows(selectedTarget.prompt, current);
      if (rows) {
        return rows.map((row) => (
          <RoleCard key={row.role} role={row.role}>
            <LineDiff base={row.baseContent} current={row.currentContent} />
          </RoleCard>
        ));
      }
      return (
        <RoleCard role="prompt">
          <LineDiff
            base={promptToText(selectedTarget.prompt)}
            current={promptToText(current)}
          />
        </RoleCard>
      );
    }

    const rows = groupMessageContentByRole(current);
    if (rows) {
      return rows.map((row) => (
        <RoleCard key={row.role} role={row.role}>
          <PlainText text={row.content} />
        </RoleCard>
      ));
    }

    const text = promptToText(current);
    return text ? (
      <RoleCard role="prompt">
        <PlainText text={text} />
      </RoleCard>
    ) : null;
  }, [diffActive, selectedTarget, current]);

  if (!body || (Array.isArray(body) && body.length === 0)) {
    return null;
  }

  // A plain title stands in for the target picker until diffing begins; without
  // a title the picker always shows (the original behavior).
  const showTitle = Boolean(title) && !diffActive;

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      {showControls && (hasTargets || showTitle) && (
        <div className="flex items-center justify-between pl-1">
          <div className="flex items-center gap-1.5">
            {showTitle ? (
              <span className="comet-body-s-accented text-foreground">
                {title}
              </span>
            ) : (
              selectedTarget && (
                <>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <button
                        type="button"
                        className="comet-body-s-accented inline-flex items-center gap-0.5 border-b border-foreground pb-px text-foreground outline-none"
                      >
                        {selectedTarget.label}
                        {selectedTarget.caption && (
                          <span className="comet-body-xs text-muted-slate">
                            {selectedTarget.caption}
                          </span>
                        )}
                        <ChevronDown className="size-3.5" />
                      </button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent
                      align="start"
                      className="min-w-[180px]"
                    >
                      <DropdownMenuLabel size="sm">
                        Compare against
                      </DropdownMenuLabel>
                      <DropdownMenuSeparator className="bg-border" />
                      {targets.map((target) => (
                        <DropdownMenuItem
                          key={target.id}
                          size="sm"
                          selected={target.id === selectedTarget.id}
                          onSelect={() => setSelectedId(target.id)}
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
              )
            )}
          </div>
          {hasTargets && (
            <button
              type="button"
              onClick={() => setShowDiff((prev) => !prev)}
              className="comet-body-s inline-flex items-center gap-1 text-foreground"
            >
              {showDiff ? (
                <GitCompareOff className="size-3.5" />
              ) : (
                <GitCompare className="size-3.5" />
              )}
              {showDiff ? "Hide diff" : "Show diff"}
            </button>
          )}
        </div>
      )}
      <div className="flex flex-col gap-1.5">{body}</div>
    </div>
  );
};

export default PromptComparison;
