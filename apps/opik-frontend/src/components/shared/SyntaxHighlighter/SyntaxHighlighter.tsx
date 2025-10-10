import React, { useState } from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

import { MODE_TYPE } from "@/components/shared/SyntaxHighlighter/constants";
import { PrettifyConfig } from "@/components/shared/SyntaxHighlighter/types";
import {
  useSyntaxHighlighterMode,
  useSyntaxHighlighterCode,
  useSyntaxHighlighterOptions,
} from "@/components/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import CodeMirrorHighlighter from "@/components/shared/SyntaxHighlighter/CodeMirrorHighlighter";
import MarkdownHighlighter from "@/components/shared/SyntaxHighlighter/MarkdownHighlighter";

export type SyntaxHighlighterProps = {
  data: object;
  prettifyConfig?: PrettifyConfig;
  preserveKey?: string;
  search?: string;
  withSearch?: boolean;
};

const SyntaxHighlighter: React.FC<SyntaxHighlighterProps> = ({
  data,
  prettifyConfig,
  preserveKey,
  search: searchValue,
  withSearch,
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
  const [localSearchValue, setLocalSearchValue] = useState<string>("");

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
      text={
        typeof code.message === "object" && code.message !== null
          ? JSON.stringify(code.message, null, 2)
          : code.message
      }
      tooltipText="Copy code"
    />
  );

  if (code.mode === MODE_TYPE.pretty) {
    return (
      <MarkdownHighlighter
        codeOutput={code}
        searchValue={searchValue}
        localSearchValue={localSearchValue}
        setLocalSearchValue={setLocalSearchValue}
        modeSelector={modeSelector}
        copyButton={copyButton}
        withSearch={withSearch}
      />
    );
  }

  return (
    <CodeMirrorHighlighter
      codeOutput={code}
      searchValue={searchValue}
      localSearchValue={localSearchValue}
      setLocalSearchValue={setLocalSearchValue}
      modeSelector={modeSelector}
      copyButton={copyButton}
      withSearch={withSearch}
    />
  );
};

export default SyntaxHighlighter;
