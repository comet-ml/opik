import React, { useMemo } from "react";
import { diffLines, diffWords, Change } from "diff";
import { cn } from "@/lib/utils";

type DiffMode = "lines" | "words";

type CodeDiffProps = {
  content1: string;
  content2: string;
  mode?: DiffMode;
};

/**
 * Renders a single change item with appropriate styling.
 */
const DiffChange: React.FC<{ change: Change; mode: DiffMode }> = ({
  change,
  mode,
}) => {
  if (!change.added && !change.removed) {
    // Unchanged content
    return <span className="text-muted-foreground">{change.value}</span>;
  }

  return (
    <span
      className={cn(
        mode === "lines" ? "block p-0.5 rounded-[2px]" : "rounded-[2px] px-0.5",
        {
          "text-diff-removed-text bg-diff-removed-bg line-through":
            change.removed,
          "text-diff-added-text bg-diff-added-bg": change.added,
        },
      )}
    >
      {change.value}
    </span>
  );
};

/**
 * TextDiff component that shows differences between two text strings.
 * Supports both line-level and word-level diff modes.
 */
const TextDiff: React.FunctionComponent<CodeDiffProps> = ({
  content1,
  content2,
  mode = "lines",
}) => {
  const changes = useMemo(() => {
    if (mode === "words") {
      return diffWords(content1, content2);
    }
    return diffLines(content1, content2);
  }, [content1, content2, mode]);

  return (
    <div
      className={cn(
        "flex w-fit",
        mode === "lines" ? "flex-col gap-[3px]" : "flex-wrap",
      )}
    >
      {changes.map((change, index) => (
        <DiffChange key={change.value + index} change={change} mode={mode} />
      ))}
    </div>
  );
};

export default TextDiff;
