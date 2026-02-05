import React from "react";
import BaseCodeMirrorBlock from "@/components/shared/CodeMirror/BaseCodeMirrorBlock";
import { PrettyLLMMessageCodeBlockProps } from "./types";

/**
 * PrettyLLMMessageCodeBlock - Code block for LLM messages
 *
 * Uses the shared BaseCodeMirrorBlock with JSON language and line wrapping enabled.
 */
const PrettyLLMMessageCodeBlock: React.FC<PrettyLLMMessageCodeBlockProps> = ({
  code,
  label = "JSON",
  className,
}) => {
  return (
    <BaseCodeMirrorBlock
      code={code}
      language="json"
      label={label}
      showLineNumbers={true}
      showCopy={true}
      wrap={true}
      className={className}
    />
  );
};

export default PrettyLLMMessageCodeBlock;
