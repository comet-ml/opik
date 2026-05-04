import React from "react";
import {
  GitCommitVertical,
  FileTerminal,
  Hash,
  Split,
  Type,
} from "lucide-react";

import { cn } from "@/lib/utils";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import Loader from "@/shared/Loader/Loader";
import { Tag } from "@/ui/tag";
import TextDiff from "@/shared/CodeDiff/TextDiff";
import { BlueprintValueType } from "@/types/agent-configs";

export type DiffMode = "unchanged" | "changed" | "added" | "removed";

const TYPE_ICON: Record<
  BlueprintValueType,
  React.ComponentType<{ className?: string }>
> = {
  [BlueprintValueType.INT]: Hash,
  [BlueprintValueType.FLOAT]: Hash,
  [BlueprintValueType.BOOLEAN]: Split,
  [BlueprintValueType.PROMPT]: FileTerminal,
  [BlueprintValueType.STRING]: Type,
};

const TYPE_BG_COLOR: Record<BlueprintValueType, string> = {
  [BlueprintValueType.INT]: "var(--color-blue)",
  [BlueprintValueType.FLOAT]: "var(--color-blue)",
  [BlueprintValueType.BOOLEAN]: "var(--color-green)",
  [BlueprintValueType.PROMPT]: "var(--color-burgundy)",
  [BlueprintValueType.STRING]: "var(--color-violet)",
};

export const DiffTypeIcon: React.FC<{
  type: BlueprintValueType;
  mode: DiffMode;
}> = ({ type, mode }) => {
  const Icon = TYPE_ICON[type] ?? Type;
  const bgColor =
    mode === "added"
      ? "var(--diff-added-text)"
      : mode === "removed"
        ? "var(--diff-removed-text)"
        : TYPE_BG_COLOR[type] ?? "var(--color-gray)";
  return (
    <span
      className="flex size-4 shrink-0 items-center justify-center rounded text-white"
      style={{ backgroundColor: bgColor }}
    >
      <Icon className="size-2.5" />
    </span>
  );
};

export const KeyCellContent: React.FC<{
  label: string;
  type: BlueprintValueType;
  mode: DiffMode;
}> = ({ label, type, mode }) => {
  const isAddedOrRemoved = mode === "added" || mode === "removed";
  const wrapperClass = cn(
    "flex min-w-0 items-center gap-1.5 rounded-md p-1",
    isAddedOrRemoved && "border ",
    mode === "removed" &&
      "bg-[var(--diff-removed-bg)] border-[var(--diff-removed-border)]",
    mode === "added" &&
      "bg-[var(--diff-added-bg)] border-[var(--diff-added-border)]",
  );
  const labelClass = cn(
    "comet-body-xs truncate",
    mode === "removed" && "text-[var(--diff-removed-text)] line-through",
    mode === "added" && "text-[var(--diff-added-text)]",
    !isAddedOrRemoved && "text-foreground",
  );
  return (
    <div className={wrapperClass}>
      <DiffTypeIcon type={type} mode={mode} />
      <span className={labelClass} title={label}>
        {label}
      </span>
    </div>
  );
};

const valueBoxClass = (mode: DiffMode) =>
  cn(
    "flex min-h-5 items-center rounded-md border px-2",
    mode === "removed" &&
      "bg-[var(--diff-removed-bg)] border-[var(--diff-removed-border)]",
    mode === "added" &&
      "bg-[var(--diff-added-bg)] border-[var(--diff-added-border)]",
    (mode === "changed" || mode === "unchanged") &&
      "bg-primary-foreground border-border",
  );

const textColorClass = (mode: DiffMode) =>
  cn(
    "comet-body-xs whitespace-pre-wrap break-words",
    mode === "removed" && "text-[var(--diff-removed-text)] line-through",
    mode === "added" && "text-[var(--diff-added-text)]",
    (mode === "changed" || mode === "unchanged") && "text-foreground",
  );

export const ValueCellContent: React.FC<{
  baseText: string;
  diffText: string;
  mode: DiffMode;
}> = ({ baseText, diffText, mode }) => {
  const text = mode === "removed" ? baseText : diffText;
  if (mode === "changed") {
    return (
      <div className={valueBoxClass(mode)}>
        <div className={textColorClass(mode)}>
          <TextDiff content1={baseText} content2={diffText} mode="words" />
        </div>
      </div>
    );
  }
  return (
    <div className={valueBoxClass(mode)}>
      <div className={textColorClass(mode)}>
        {text || <span className="italic text-muted-slate">(empty)</span>}
      </div>
    </div>
  );
};

const PromptCommitTag: React.FC<{ commit: string; mode: DiffMode }> = ({
  commit,
  mode,
}) => (
  <Tag
    className={cn(
      "flex w-fit items-center gap-1",
      mode === "removed" &&
        "border-[var(--diff-removed-border)] bg-[var(--diff-removed-bg)] text-[var(--diff-removed-text)] line-through",
      mode === "added" &&
        "border-[var(--diff-added-border)] bg-[var(--diff-added-bg)] text-[var(--diff-added-text)]",
    )}
    variant="gray"
    size="sm"
    title={commit}
  >
    <GitCommitVertical className="size-3.5 shrink-0" />
    {commit.slice(0, 8)}
  </Tag>
);

export const PromptCellContent: React.FC<{
  baseCommit: string;
  diffCommit: string;
  baseTemplate?: string;
  diffTemplate?: string;
  mode: DiffMode;
}> = ({ baseCommit, diffCommit, baseTemplate, diffTemplate, mode }) => {
  const { data: basePrompt, isLoading: baseLoading } = usePromptByCommit(
    { commitId: baseCommit },
    { enabled: !!baseCommit && baseTemplate === undefined },
  );
  const { data: diffPrompt, isLoading: diffLoading } = usePromptByCommit(
    { commitId: diffCommit },
    { enabled: !!diffCommit && diffTemplate === undefined },
  );

  const isLoading =
    (baseTemplate === undefined && baseLoading) ||
    (diffTemplate === undefined && diffLoading);

  if (isLoading) {
    return (
      <div className={valueBoxClass(mode)}>
        <Loader />
      </div>
    );
  }

  const baseText =
    baseTemplate ?? basePrompt?.requested_version?.template ?? "";
  const diffText =
    diffTemplate ?? diffPrompt?.requested_version?.template ?? "";
  const commitsChanged = baseCommit !== diffCommit;

  const renderTags = () => {
    if (mode === "removed" && baseCommit) {
      return <PromptCommitTag commit={baseCommit} mode="removed" />;
    }
    if (mode === "added" && diffCommit) {
      return <PromptCommitTag commit={diffCommit} mode="added" />;
    }
    if (commitsChanged) {
      return (
        <>
          {baseCommit && <PromptCommitTag commit={baseCommit} mode="removed" />}
          {diffCommit && <PromptCommitTag commit={diffCommit} mode="added" />}
        </>
      );
    }
    if (diffCommit) {
      return <PromptCommitTag commit={diffCommit} mode="unchanged" />;
    }
    return null;
  };

  const tags = renderTags();
  const text = mode === "removed" ? baseText : diffText;

  return (
    <div className="flex flex-col gap-2">
      {tags && <div className="flex flex-wrap items-center gap-2">{tags}</div>}
      <div className={valueBoxClass(mode)}>
        <div className={cn("comet-code", textColorClass(mode))}>
          {mode === "changed" ? (
            <TextDiff content1={baseText} content2={diffText} mode="words" />
          ) : (
            text || <span className="italic text-muted-slate">(empty)</span>
          )}
        </div>
      </div>
    </div>
  );
};
