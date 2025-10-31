import React, { useMemo } from "react";
import { diffLines } from "diff";
import { cn } from "@/lib/utils";

type CodeDiffProps = {
  content1: string;
  content2: string;
};

const TextDiff: React.FunctionComponent<CodeDiffProps> = ({
  content1,
  content2,
}) => {
  return useMemo(() => {
    const changes = diffLines(content1, content2);

    return (
      <div className="flex w-fit flex-col gap-[3px]">
        {changes.map((c, index) => (
          <div
            key={c.value + index}
            className={cn("p-0.5 rounded-[2px]", {
              "text-diff-removed-text bg-diff-removed-bg": c.removed,
              "text-diff-added-text bg-diff-added-bg": c.added,
            })}
          >
            {c.value}
          </div>
        ))}
      </div>
    );
  }, [content1, content2]);
};

export default TextDiff;
