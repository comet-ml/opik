import React, { useMemo } from "react";
import { diffChars, diffWords, diffLines, Change } from "diff";
import { cn } from "@/lib/utils";

export enum DIFF_MODE {
  chars = "chars",
  words = "words",
  lines = "lines",
  none = "none",
}

type CodeDiffProps = {
  content1: string;
  content2: string;
  mode?: DIFF_MODE;
};

const TextDiff: React.FunctionComponent<CodeDiffProps> = ({
  content1,
  content2,
  mode = DIFF_MODE.words,
}) => {
  const changes = useMemo(() => {
    let retVal: Change[] = [];

    switch (mode) {
      case DIFF_MODE.chars:
        retVal = diffChars(content1, content2);
        break;
      case DIFF_MODE.words:
        retVal = diffWords(content1, content2);
        break;
      case DIFF_MODE.lines:
        retVal = diffLines(content1, content2);
        break;
    }

    return retVal;
  }, [mode, content1, content2]);

  console.log(changes);
  // TODO lala key
  return (
    <>
      {changes.map((c, index) => (
        <span
          key={c.value + index}
          className={cn({
            "text-red-500 line-through pr-0.5": c.removed,
            "text-green-500": c.added,
          })}
        >
          {c.value}
        </span>
      ))}
    </>
  );
};

export default TextDiff;
