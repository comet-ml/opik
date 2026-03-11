import React from "react";
import { GitCommitVertical } from "lucide-react";

import { cn } from "@/lib/utils";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import Loader from "@/components/shared/Loader/Loader";
import { Tag } from "@/components/ui/tag";
import { TableCell } from "@/components/ui/table";
import TextDiff from "@/components/shared/CodeDiff/TextDiff";

export type DiffSide = "base" | "diff";

const SIDE_STYLES = {
  base: "border-[var(--diff-removed-border)] bg-[var(--diff-removed-bg)] text-[var(--diff-removed-text)]",
  diff: "border-[var(--diff-added-border)] bg-[var(--diff-added-bg)] text-[var(--diff-added-text)]",
} as const;

export const DiffCellBox: React.FC<{
  text: string;
  changed: boolean;
  side: DiffSide;
  className?: string;
}> = ({ text, changed, side, className }) => (
  <div
    className={cn(
      "comet-body-s whitespace-pre-wrap break-words rounded-md border p-2 text-sm",
      changed
        ? SIDE_STYLES[side]
        : "bg-primary-foreground text-muted-foreground",
      className,
    )}
  >
    {text || "(empty)"}
  </div>
);

export const EmptyDiffCell: React.FC = () => (
  <span className="comet-body-xs italic text-muted-slate">—</span>
);

export const PromptDiffPair: React.FC<{
  baseCommit: string;
  diffCommit: string;
  baseTemplate?: string;
  diffTemplate?: string;
}> = ({ baseCommit, diffCommit, baseTemplate, diffTemplate }) => {
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
      <>
        <TableCell className="w-1/2 py-3 pr-2 align-top">
          <Loader />
        </TableCell>
        <TableCell className="w-1/2 py-3 pl-2 align-top">
          <Loader />
        </TableCell>
      </>
    );
  }

  const baseText =
    baseTemplate ?? basePrompt?.requested_version?.template ?? "";
  const diffText =
    diffTemplate ?? diffPrompt?.requested_version?.template ?? "";
  const changed = baseText !== diffText;
  const commitsChanged = baseCommit !== diffCommit;

  const renderDiffContent = (text: string, isBase: boolean) => {
    if (!changed) {
      return (
        <div className="comet-code max-h-48 overflow-y-auto whitespace-pre-wrap break-words rounded-md border bg-primary-foreground p-2 text-sm text-muted-foreground">
          {text || "(empty)"}
        </div>
      );
    }

    return (
      <div className="comet-code max-h-48 overflow-y-auto whitespace-pre-wrap break-words rounded-md border bg-primary-foreground px-2.5 py-1.5 text-sm">
        <TextDiff content1={baseText} content2={isBase ? baseText : diffText} />
      </div>
    );
  };

  return (
    <>
      <TableCell className="w-1/2 py-3 pr-2 align-top">
        {baseCommit ? (
          <div className="flex flex-col gap-1">
            <Tag
              className={cn(
                "flex w-fit items-center gap-1",
                commitsChanged &&
                  "border-[var(--diff-removed-border)] bg-[var(--diff-removed-bg)] text-[var(--diff-removed-text)]",
              )}
              variant="gray"
              size="sm"
              title={baseCommit}
            >
              <GitCommitVertical className="size-3.5 shrink-0" />
              {baseCommit.slice(0, 8)}
            </Tag>
            {renderDiffContent(baseText, true)}
          </div>
        ) : (
          <EmptyDiffCell />
        )}
      </TableCell>
      <TableCell className="w-1/2 py-3 pl-2 align-top">
        {diffCommit ? (
          <div className="flex flex-col gap-1">
            <Tag
              className={cn(
                "flex w-fit items-center gap-1",
                commitsChanged &&
                  "border-[var(--diff-added-border)] bg-[var(--diff-added-bg)] text-[var(--diff-added-text)]",
              )}
              variant="gray"
              size="sm"
              title={diffCommit}
            >
              <GitCommitVertical className="size-3.5 shrink-0" />
              {diffCommit.slice(0, 8)}
            </Tag>
            {renderDiffContent(diffText, false)}
          </div>
        ) : (
          <EmptyDiffCell />
        )}
      </TableCell>
    </>
  );
};
