import React, { useMemo, useState } from "react";
import { diffLines } from "diff";
import { ChevronDown, GitCompareArrows } from "lucide-react";

import { cn } from "@/lib/utils";
import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from "@/ui/dropdown-menu";
import { PromptComparisonTarget } from "./promptComparisonTargets";
import {
  buildRoleDiffRows,
  groupMessageContentByRole,
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
  className?: string;
};

const promptToText = (prompt: unknown): string => {
  if (prompt === null || prompt === undefined) return "";
  if (typeof prompt === "string") return prompt;
  return JSON.stringify(prompt, null, 2);
};

const roleLabel = (role: string): string =>
  LLM_MESSAGE_ROLE_NAME_MAP[role as keyof typeof LLM_MESSAGE_ROLE_NAME_MAP] ??
  role;

/**
 * Line-level diff: unchanged lines keep the full foreground color, added lines
 * get a green band and removed lines a struck-through red band (matching the
 * Figma trial-details diff — no word-level inline highlighting, no tag pills).
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
        {roleLabel(role)}
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
  className,
}) => {
  const fallbackId = targets[0]?.id;
  const initialId =
    defaultTargetId && targets.some((t) => t.id === defaultTargetId)
      ? defaultTargetId
      : fallbackId;

  const [selectedId, setSelectedId] = useState<string | undefined>(initialId);
  const [showDiff, setShowDiff] = useState(true);

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

  return (
    <div className={cn("flex flex-col gap-3", className)}>
      {hasTargets && selectedTarget && (
        <div className="flex items-center justify-between pl-1">
          <div className="flex items-center gap-1.5">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <button
                  type="button"
                  className="comet-body-s-accented inline-flex items-center gap-0.5 border-b border-foreground pb-px text-foreground outline-none"
                >
                  {selectedTarget.label}
                  <ChevronDown className="size-3.5" />
                </button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="start">
                <DropdownMenuRadioGroup
                  value={selectedTarget.id}
                  onValueChange={setSelectedId}
                >
                  {targets.map((target) => (
                    <DropdownMenuRadioItem key={target.id} value={target.id}>
                      {target.label}
                    </DropdownMenuRadioItem>
                  ))}
                </DropdownMenuRadioGroup>
              </DropdownMenuContent>
            </DropdownMenu>
            <span className="comet-body-s-accented text-foreground">
              → {currentLabel}
            </span>
          </div>
          <button
            type="button"
            onClick={() => setShowDiff((prev) => !prev)}
            className="comet-body-s inline-flex items-center gap-1 text-foreground"
          >
            <GitCompareArrows className="size-3.5" />
            {showDiff ? "Hide diff" : "Show diff"}
          </button>
        </div>
      )}
      <div className="flex flex-col gap-1.5">{body}</div>
    </div>
  );
};

export default PromptComparison;
