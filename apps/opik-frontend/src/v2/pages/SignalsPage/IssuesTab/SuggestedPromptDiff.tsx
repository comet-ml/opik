import React from "react";
import { PromptDiffLine, PROMPT_DIFF_LINE_TYPE } from "@/types/signals";

const LINE_STYLE: Record<PROMPT_DIFF_LINE_TYPE, React.CSSProperties> = {
  [PROMPT_DIFF_LINE_TYPE.context]: {},
  [PROMPT_DIFF_LINE_TYPE.removed]: {
    backgroundColor: "var(--diff-removed-bg)",
    color: "var(--diff-removed-text)",
  },
  [PROMPT_DIFF_LINE_TYPE.added]: {
    backgroundColor: "var(--diff-added-bg)",
    color: "var(--diff-added-text)",
  },
};

const LINE_PREFIX: Record<PROMPT_DIFF_LINE_TYPE, string> = {
  [PROMPT_DIFF_LINE_TYPE.context]: " ",
  [PROMPT_DIFF_LINE_TYPE.removed]: "−",
  [PROMPT_DIFF_LINE_TYPE.added]: "+",
};

type SuggestedPromptDiffProps = {
  lines: PromptDiffLine[];
};

const SuggestedPromptDiff: React.FC<SuggestedPromptDiffProps> = ({ lines }) => {
  return (
    <div className="comet-code overflow-hidden rounded-md bg-[#F8FAFC] text-xs text-foreground">
      {lines.map((line, index) => (
        <div
          key={index}
          className="flex gap-1 whitespace-pre-wrap break-words px-2"
          style={LINE_STYLE[line.type]}
        >
          <span className="select-none">{LINE_PREFIX[line.type]}</span>
          <span>{line.text}</span>
        </div>
      ))}
    </div>
  );
};

export default SuggestedPromptDiff;
