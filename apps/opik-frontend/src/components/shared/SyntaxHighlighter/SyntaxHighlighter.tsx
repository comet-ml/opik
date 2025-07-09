import React from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

import { MODE_TYPE } from "./constants";
import { SyntaxHighlighterProps } from "./types";
import {
  useSyntaxHighlighterMode,
  useSyntaxHighlighterCode,
  useSyntaxHighlighterOptions,
} from "./hooks";
import CodeMirrorHighlighter from "./CodeMirrorHighlighter";
import MarkdownHighlighter from "./MarkdownHighlighter";

const SyntaxHighlighter: React.FC<SyntaxHighlighterProps> = ({
  data,
  prettifyConfig,
  preserveKey,
  search: searchValue,
}) => {
  const { mode, setMode } = useSyntaxHighlighterMode(
    prettifyConfig,
    preserveKey,
  );
  const code = useSyntaxHighlighterCode(data, mode, prettifyConfig);
  const options = useSyntaxHighlighterOptions(
    prettifyConfig,
    code.canBePrettified,
  );

  const handleModeChange = (newMode: string) => {
    setMode(newMode as MODE_TYPE);
  };

  const modeSelector = (
    <SelectBox
      value={code.mode}
      onChange={handleModeChange}
      options={options}
      className="w-fit"
      variant="ghost"
    />
  );

  const copyButton = (
    <CopyButton
      message="Successfully copied code"
      text={code.message}
      tooltipText="Copy code"
    />
  );

  if (code.mode === MODE_TYPE.pretty) {
    return (
      <MarkdownHighlighter
        codeOutput={code}
        searchValue={searchValue}
        modeSelector={modeSelector}
        copyButton={copyButton}
      />
    );
  }

  return (
    <CodeMirrorHighlighter
      codeOutput={code}
      searchValue={searchValue}
      modeSelector={modeSelector}
      copyButton={copyButton}
    />
  );
};

export default SyntaxHighlighter;
