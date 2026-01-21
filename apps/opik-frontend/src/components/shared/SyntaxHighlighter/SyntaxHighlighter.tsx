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
import {
  LLMMessagesHighlighter,
  detectLLMMessages,
} from "@/components/shared/SyntaxHighlighter/llmMessages";
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
  provider?: string;
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
  provider,
}) => {
  const { mode, setMode } = useSyntaxHighlighterMode(
    prettifyConfig,
    preserveKey,
  );
  const code = useSyntaxHighlighterCode(data, mode, prettifyConfig, provider);
  const options = useSyntaxHighlighterOptions(
    prettifyConfig,
    code.canBePrettified,
  );
  const [localSearchValue, setLocalSearchValue] = useState<string>("");

  // Detect if LLM messages format is supported for custom pretty mode
  const llmMessagesDetection = useMemo(() => {
    return detectLLMMessages(data, prettifyConfig, provider);
  }, [data, prettifyConfig, provider]);

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
    // Use LLMMessagesHighlighter for supported LLM format data
    if (llmMessagesDetection.supported && llmMessagesDetection.provider) {
      return (
        <LLMMessagesHighlighter
          codeOutput={code}
          data={data}
          prettifyConfig={prettifyConfig}
          provider={llmMessagesDetection.provider}
          modeSelector={modeSelector}
          copyButton={copyButton}
          scrollRef={scrollRef}
          onScroll={handleScroll}
          maxHeight={maxHeight}
          preserveKey={preserveKey}
        />
      );
    }

    // Fall back to MarkdownHighlighter for unsupported formats
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
