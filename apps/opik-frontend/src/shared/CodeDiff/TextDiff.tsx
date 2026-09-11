import React, { useMemo } from "react";
import { diffLines, diffWords, Change } from "diff";
import { cn } from "@/lib/utils";

type DiffMode = "lines" | "words";
type DiffSide = "base" | "diff" | "both";

type CodeDiffProps = {
  content1: string;
  content2: string;
  mode?: DiffMode;
  /**
   * Restricts which side of the diff is rendered:
   * - "base" shows base content with removals highlighted (no additions)
   * - "diff" shows diff content with additions highlighted (no removals)
   * - "both" (default) renders the full unified diff
   */
  side?: DiffSide;
};

/**
 * Renders a word-level change span (inline).
 */
const WordChange: React.FC<{ change: Change }> = ({ change }) => {
  if (!change.added && !change.removed) {
    return <span className="text-muted-foreground">{change.value}</span>;
  }

  return (
    <span
      className={cn("rounded-[2px] px-0.5", {
        "text-diff-removed-text bg-diff-removed-bg line-through":
          change.removed,
        "text-diff-added-text bg-diff-added-bg": change.added,
      })}
    >
      {change.value}
    </span>
  );
};

/**
 * Renders a line-level change block.
 */
const LineChange: React.FC<{ change: Change }> = ({ change }) => {
  if (!change.added && !change.removed) {
    return (
      <span className="whitespace-pre-wrap text-muted-foreground">
        {change.value}
      </span>
    );
  }

  return (
    <span
      className={cn("block whitespace-pre-wrap rounded-[2px] p-0.5", {
        "text-diff-removed-text bg-diff-removed-bg line-through":
          change.removed,
        "text-diff-added-text bg-diff-added-bg": change.added,
      })}
    >
      {change.value}
    </span>
  );
};

const LINE_PAIR_CHANGE_THRESHOLD = 0.6;

/**
 * Returns the ratio of changed characters in a set of word-level changes.
 */
const getChangedRatio = (changes: Change[]): number => {
  let changed = 0;
  let total = 0;
  for (const c of changes) {
    total += c.value.length;
    if (c.added || c.removed) changed += c.value.length;
  }
  return total === 0 ? 0 : changed / total;
};

/**
 * Renders a paired line with inline word-level highlights.
 * Falls back to separate removed/added blocks when lines are too different.
 */
const RefinedLineDiff: React.FC<{
  removedLine: string;
  addedLine: string;
}> = ({ removedLine, addedLine }) => {
  const wordChanges = useMemo(
    () => diffWords(removedLine, addedLine),
    [removedLine, addedLine],
  );

  const tooNoisy = getChangedRatio(wordChanges) > LINE_PAIR_CHANGE_THRESHOLD;

  if (tooNoisy) {
    return (
      <div className="flex flex-col gap-[3px]">
        <span className="block rounded-[2px] bg-diff-removed-bg p-0.5 text-diff-removed-text line-through">
          {removedLine}
        </span>
        <span className="block rounded-[2px] bg-diff-added-bg p-0.5 text-diff-added-text">
          {addedLine}
        </span>
      </div>
    );
  }

  return (
    <div className="whitespace-pre-wrap break-words">
      {wordChanges.map((change, i) => (
        <WordChange
          key={`${change.added ? "add" : change.removed ? "rem" : "eq"}-${i}`}
          change={change}
        />
      ))}
    </div>
  );
};

/**
 * Splits a diff value into individual lines, removing trailing empty
 * entries caused by trailing newlines.
 */
const splitLines = (value: string): string[] => {
  const lines = value.split("\n");
  if (lines.length > 0 && lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
};

type DiffSegment =
  | { type: "unchanged"; value: string }
  | { type: "line-change"; change: Change }
  | { type: "refined"; removedLine: string; addedLine: string };

/**
 * Takes raw line-level changes and produces refined segments:
 * - Unchanged blocks pass through
 * - Adjacent removed+added blocks are paired line-by-line for word-level diff
 * - Unpaired removed/added lines are shown as full line changes
 */
const buildRefinedSegments = (changes: Change[]): DiffSegment[] => {
  const segments: DiffSegment[] = [];
  let i = 0;

  while (i < changes.length) {
    const change = changes[i];

    if (!change.added && !change.removed) {
      segments.push({ type: "unchanged", value: change.value });
      i++;
      continue;
    }

    // Collect consecutive removed then added blocks
    const removedLines: string[] = [];
    const addedLines: string[] = [];

    while (i < changes.length && changes[i].removed) {
      removedLines.push(...splitLines(changes[i].value));
      i++;
    }
    while (i < changes.length && changes[i].added) {
      addedLines.push(...splitLines(changes[i].value));
      i++;
    }

    // Pair up lines for refined word diff
    const paired = Math.min(removedLines.length, addedLines.length);
    for (let j = 0; j < paired; j++) {
      segments.push({
        type: "refined",
        removedLine: removedLines[j],
        addedLine: addedLines[j],
      });
    }

    // Leftover unpaired removed lines
    for (let j = paired; j < removedLines.length; j++) {
      segments.push({
        type: "line-change",
        change: {
          value: removedLines[j] + "\n",
          removed: true,
          added: false,
          count: 1,
        },
      });
    }

    // Leftover unpaired added lines
    for (let j = paired; j < addedLines.length; j++) {
      segments.push({
        type: "line-change",
        change: {
          value: addedLines[j] + "\n",
          removed: false,
          added: true,
          count: 1,
        },
      });
    }
  }

  return segments;
};

/**
 * TextDiff component that shows differences between two text strings.
 * Supports both line-level and word-level diff modes.
 *
 * In "words" mode, uses a hybrid approach: line-level diff first to identify
 * changed regions, then word-level diff within each changed line pair for
 * precise inline highlighting while keeping unchanged lines clean.
 */
const TextDiff: React.FunctionComponent<CodeDiffProps> = ({
  content1,
  content2,
  mode = "lines",
  side = "both",
}) => {
  const result = useMemo(() => {
    if (mode === "words") {
      const lineChanges = diffLines(content1, content2);
      const hasAnyChange = lineChanges.some((c) => c.added || c.removed);
      if (!hasAnyChange) {
        return { kind: "unchanged" as const, lineChanges };
      }
      return {
        kind: "refined" as const,
        segments: buildRefinedSegments(lineChanges),
      };
    }
    return {
      kind: "lines" as const,
      lineChanges: diffLines(content1, content2),
    };
  }, [content1, content2, mode]);

  if (result.kind === "unchanged") {
    return (
      <div className="whitespace-pre-wrap break-words text-muted-foreground">
        {content2}
      </div>
    );
  }

  if (result.kind === "refined") {
    return (
      <div className="flex flex-col gap-[3px]">
        {result.segments.map((seg, i) => {
          if (seg.type === "unchanged") {
            return (
              <span
                key={`${seg.type}-${i}`}
                className="whitespace-pre-wrap text-muted-foreground"
              >
                {seg.value}
              </span>
            );
          }
          if (seg.type === "refined") {
            if (side === "base") {
              return (
                <LineChange
                  key={`refined-base-${i}`}
                  change={{
                    value: seg.removedLine + "\n",
                    removed: true,
                    added: false,
                    count: 1,
                  }}
                />
              );
            }
            if (side === "diff") {
              return (
                <LineChange
                  key={`refined-diff-${i}`}
                  change={{
                    value: seg.addedLine + "\n",
                    removed: false,
                    added: true,
                    count: 1,
                  }}
                />
              );
            }
            return (
              <RefinedLineDiff
                key={`${seg.type}-${i}`}
                removedLine={seg.removedLine}
                addedLine={seg.addedLine}
              />
            );
          }
          // line-change segment
          if (side === "base" && seg.change.added) return null;
          if (side === "diff" && seg.change.removed) return null;
          return <LineChange key={`line-change-${i}`} change={seg.change} />;
        })}
      </div>
    );
  }

  return (
    <div className="flex w-fit flex-col gap-[3px]">
      {result.lineChanges.map((change, index) => {
        if (side === "base" && change.added) return null;
        if (side === "diff" && change.removed) return null;
        return (
          <LineChange
            key={`${
              change.added ? "add" : change.removed ? "rem" : "eq"
            }-${index}`}
            change={change}
          />
        );
      })}
    </div>
  );
};

export default TextDiff;
