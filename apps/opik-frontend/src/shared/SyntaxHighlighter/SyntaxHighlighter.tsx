import React, { useState } from "react";
import SelectBox from "@/shared/SelectBox/SelectBox";
import CopyButton from "@/shared/CopyButton/CopyButton";

import { MODE_TYPE } from "@/shared/SyntaxHighlighter/constants";
import { PrettifyConfig } from "@/shared/SyntaxHighlighter/types";
import {
  useSyntaxHighlighterMode,
  useSyntaxHighlighterCode,
  useSyntaxHighlighterOptions,
} from "@/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import { useScrollRestoration } from "@/shared/SyntaxHighlighter/hooks/useScrollRestoration";
import CodeMirrorHighlighter from "@/shared/SyntaxHighlighter/CodeMirrorHighlighter";
import MarkdownHighlighter from "@/shared/SyntaxHighlighter/MarkdownHighlighter";
import { OnChangeFn } from "@/types/shared";

export type SyntaxHighlighterProps = {
  data: object;
  prettifyConfig?: PrettifyConfig;
  preserveKey?: string;
  search?: string;
  withSearch?: boolean;
  scrollPosition?: number;
  onScrollPositionChange?: OnChangeFn<number>;
  maxHeight?: string;
};

const SyntaxHighlighter: React.FC<SyntaxHighlighterProps> = ({
  data,
  prettifyConfig,
  preserveKey,
  search: searchValue,
  withSearch,
  scrollPosition,
  onScrollPositionChange,
  maxHeight,
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

  // Scroll management hook
  const { scrollRef, handleScroll } = useScrollRestoration({
    data,
    scrollPosition,
    onScrollPositionChange,
  });

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
        localSearchValue={localSearchValue}
        setLocalSearchValue={setLocalSearchValue}
        modeSelector={modeSelector}
        copyButton={copyButton}
        withSearch={withSearch}
        scrollRef={scrollRef}
        onScroll={handleScroll}
        maxHeight={maxHeight}
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
      scrollRef={scrollRef}
      onScroll={handleScroll}
      maxHeight={maxHeight}
    />
  );
};

export default SyntaxHighlighter;
