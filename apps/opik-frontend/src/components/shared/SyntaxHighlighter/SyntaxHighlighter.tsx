import React, { useState, useMemo } from "react";
import SelectBox from "@/components/shared/SelectBox/SelectBox";
import CopyButton from "@/components/shared/CopyButton/CopyButton";

import { MODE_TYPE } from "@/components/shared/SyntaxHighlighter/constants";
import { PrettifyConfig } from "@/components/shared/SyntaxHighlighter/types";
import {
  useSyntaxHighlighterMode,
  useSyntaxHighlighterCode,
  useSyntaxHighlighterOptions,
} from "@/components/shared/SyntaxHighlighter/hooks/useSyntaxHighlighterHooks";
import { useScrollRestoration } from "@/components/shared/SyntaxHighlighter/hooks/useScrollRestoration";
import CodeMirrorHighlighter from "@/components/shared/SyntaxHighlighter/CodeMirrorHighlighter";
import MarkdownHighlighter from "@/components/shared/SyntaxHighlighter/MarkdownHighlighter";
import { ExpandedState, OnChangeFn } from "@tanstack/react-table";

export type SyntaxHighlighterProps = {
  data: object;
  prettifyConfig?: PrettifyConfig;
  preserveKey?: string;
  search?: string;
  withSearch?: boolean;
  controlledExpanded?: ExpandedState;
  onExpandedChange?: (
    updaterOrValue: ExpandedState | ((old: ExpandedState) => ExpandedState),
  ) => void;
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
  controlledExpanded,
  onExpandedChange,
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

  const copyText = useMemo(() => {
    if (typeof code.message === "object" && code.message !== null) {
      try {
        return JSON.stringify(code.message, null, 2);
      } catch (error) {
        // Fallback for objects that can't be JSON stringified
        return String(code.message);
      }
    }
    return String(code.message);
  }, [code.message]);

  const copyButton = (
    <CopyButton
      message="Successfully copied code"
      text={copyText}
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
        controlledExpanded={controlledExpanded}
        onExpandedChange={onExpandedChange}
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
