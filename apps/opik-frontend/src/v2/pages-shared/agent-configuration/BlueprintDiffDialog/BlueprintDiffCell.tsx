import React from "react";
import { GitCommitVertical } from "lucide-react";

import { cn } from "@/lib/utils";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import Loader from "@/shared/Loader/Loader";
import { Tag } from "@/ui/tag";
import TextDiff from "@/shared/CodeDiff/TextDiff";
import { BlueprintValueType } from "@/types/agent-configs";
import BlueprintTypeIcon from "@/v2/pages-shared/traces/ConfigurationTab/BlueprintTypeIcon";

export type DiffMode = "unchanged" | "changed" | "added" | "removed";

export type PromptSide = {
  commit: string;
  template?: string;
};

const MODE_TOKENS: Record<
  Exclude<DiffMode, "unchanged" | "changed">,
  { surface: string; text: string; lineThrough?: boolean }
> = {
  added: {
    surface: "bg-diff-added-bg border-diff-added-border",
    text: "text-diff-added-text",
  },
  removed: {
    surface: "bg-diff-removed-bg border-diff-removed-border",
    text: "text-diff-removed-text",
    lineThrough: true,
  },
};

const tokenSurface = (mode: DiffMode) =>
  mode === "added" || mode === "removed"
    ? MODE_TOKENS[mode].surface
    : "bg-primary-foreground border-border";

const tokenText = (mode: DiffMode) =>
  cn(
    mode === "added" && MODE_TOKENS.added.text,
    mode === "removed" && [MODE_TOKENS.removed.text, "line-through"],
    mode === "changed" && "text-foreground",
    mode === "unchanged" && "text-foreground",
  );

export const KeyCellContent: React.FC<{
  label: string;
  type: BlueprintValueType;
  mode: DiffMode;
}> = ({ label, type, mode }) => {
  const isAddedOrRemoved = mode === "added" || mode === "removed";
  const wrapperClass = cn(
    "flex min-w-0 items-center gap-1.5 rounded-md p-1",
    isAddedOrRemoved && ["border", MODE_TOKENS[mode].surface],
  );
  const labelClass = cn(
    "comet-body-xs truncate",
    isAddedOrRemoved
      ? [
          MODE_TOKENS[mode].text,
          MODE_TOKENS[mode].lineThrough && "line-through",
        ]
      : "text-foreground",
  );
  const tone =
    mode === "added" ? "added" : mode === "removed" ? "removed" : undefined;
  return (
    <div className={wrapperClass}>
      <BlueprintTypeIcon type={type} size="sm" tone={tone} />
      <span className={labelClass} title={label}>
        {label}
      </span>
    </div>
  );
};

const valueBoxClass = (mode: DiffMode) =>
  cn("flex min-h-6 items-center rounded-md border px-2", tokenSurface(mode));

export const ValueCellContent: React.FC<{
  baseText: string;
  diffText: string;
  mode: DiffMode;
}> = ({ baseText, diffText, mode }) => {
  if (mode === "changed") {
    return (
      <div className={valueBoxClass(mode)}>
        <div
          className={cn(
            "comet-body-xs whitespace-pre-wrap break-words",
            tokenText(mode),
          )}
        >
          <TextDiff content1={baseText} content2={diffText} mode="words" />
        </div>
      </div>
    );
  }
  const text = mode === "removed" ? baseText : diffText;
  return (
    <div
      className={cn(
        valueBoxClass(mode),
        "comet-body-xs whitespace-pre-wrap break-words",
        tokenText(mode),
      )}
    >
      {text || <span className="italic text-muted-slate">(empty)</span>}
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
      (mode === "added" || mode === "removed") && [
        "border",
        MODE_TOKENS[mode].surface,
        MODE_TOKENS[mode].text,
        MODE_TOKENS[mode].lineThrough && "line-through",
      ],
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
  base: PromptSide;
  diff: PromptSide;
  mode: DiffMode;
}> = ({ base, diff, mode }) => {
  const { data: basePrompt, isLoading: baseLoading } = usePromptByCommit(
    { commitId: base.commit },
    { enabled: !!base.commit && base.template === undefined },
  );
  const { data: diffPrompt, isLoading: diffLoading } = usePromptByCommit(
    { commitId: diff.commit },
    { enabled: !!diff.commit && diff.template === undefined },
  );

  const isLoading =
    (base.template === undefined && baseLoading) ||
    (diff.template === undefined && diffLoading);

  if (isLoading) {
    return (
      <div className={valueBoxClass(mode)}>
        <Loader />
      </div>
    );
  }

  const baseText =
    base.template ?? basePrompt?.requested_version?.template ?? "";
  const diffText =
    diff.template ?? diffPrompt?.requested_version?.template ?? "";
  const commitsChanged = base.commit !== diff.commit;

  const renderTags = () => {
    if (mode === "removed" && base.commit) {
      return <PromptCommitTag commit={base.commit} mode="removed" />;
    }
    if (mode === "added" && diff.commit) {
      return <PromptCommitTag commit={diff.commit} mode="added" />;
    }
    if (commitsChanged) {
      return (
        <>
          {base.commit && (
            <PromptCommitTag commit={base.commit} mode="removed" />
          )}
          {diff.commit && <PromptCommitTag commit={diff.commit} mode="added" />}
        </>
      );
    }
    if (diff.commit) {
      return <PromptCommitTag commit={diff.commit} mode="unchanged" />;
    }
    return null;
  };

  const tags = renderTags();
  const text = mode === "removed" ? baseText : diffText;

  return (
    <div className="flex flex-col gap-2">
      {tags && <div className="flex flex-wrap items-center gap-2">{tags}</div>}
      <div className={valueBoxClass(mode)}>
        <div className={cn("comet-code", tokenText(mode))}>
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
