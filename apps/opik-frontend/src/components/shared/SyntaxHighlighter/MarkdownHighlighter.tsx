import React, { ReactNode, useMemo, useRef, useState } from "react";
import { CodeOutput } from "./types";
import SyntaxHighlighterLayout from "./SyntaxHighlighterLayout";
import { useMarkdownSearch } from "./hooks/useMarkdownSearch";
import { cn, isStringMarkdown } from "@/lib/utils";
import ReactMarkdown from "react-markdown";
import rehypeRaw from "rehype-raw";
import remarkBreaks from "remark-breaks";
import remarkGfm from "node_modules/remark-gfm/lib";
import { isNull } from "lodash";
import SearchHighlighter from "./SearchHighlighter";

export interface MarkdownHighlighterProps {
  searchValue?: string;
  codeOutput: CodeOutput;
  modeSelector: ReactNode;
  copyButton: ReactNode;
  withSearch?: boolean;
}

const MarkdownHighlighter: React.FC<MarkdownHighlighterProps> = ({
  codeOutput,
  searchValue,
  modeSelector,
  copyButton,
  withSearch,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [localSearchValue, setLocalSearchValue] = useState<string>("");
  const {
    searchPlugin,
    searchPlainText,
    findNext,
    findPrev,
    currentMatchIndex,
    totalMatches,
  } = useMarkdownSearch({
    searchValue: localSearchValue || searchValue,
    container: containerRef.current,
  });

  const markdownPreview = useMemo(() => {
    if (isNull(codeOutput.message)) return "";

    if (isStringMarkdown(codeOutput.message)) {
      return (
        <ReactMarkdown
          className={cn("prose comet-markdown")}
          remarkPlugins={[remarkBreaks, remarkGfm, searchPlugin]}
          rehypePlugins={[rehypeRaw]}
        >
          {codeOutput.message}
        </ReactMarkdown>
      );
    }

    return (
      <div className="comet-markdown whitespace-pre-wrap">
        {searchPlainText(codeOutput.message)}
      </div>
    );
  }, [codeOutput.message, searchPlugin, searchPlainText]);

  return (
    <SyntaxHighlighterLayout
      leftHeader={modeSelector}
      rightHeader={
        <>
          {withSearch && (
            <SearchHighlighter
              searchValue={localSearchValue}
              onSearch={setLocalSearchValue}
              onPrev={findPrev}
              onNext={findNext}
              currentMatchIndex={currentMatchIndex}
              totalMatches={totalMatches}
            />
          )}
          {copyButton}
        </>
      }
    >
      <div
        className="comet-markdown max-h-[300px] overflow-y-auto whitespace-pre-wrap p-3"
        ref={containerRef}
      >
        {markdownPreview}
      </div>
    </SyntaxHighlighterLayout>
  );
};

export default MarkdownHighlighter;
