import React, { ReactNode, useMemo } from "react";
import { CodeOutput } from "@/components/shared/SyntaxHighlighter/types";
import SyntaxHighlighterLayout from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterLayout";
import { useMarkdownSearch } from "@/components/shared/SyntaxHighlighter/hooks/useMarkdownSearch";
import { cn, isStringMarkdown } from "@/lib/utils";
import ReactMarkdown from "react-markdown";
import rehypeRaw from "rehype-raw";
import remarkBreaks from "remark-breaks";
import remarkGfm from "node_modules/remark-gfm/lib";
import { isNull } from "lodash";
import SyntaxHighlighterSearch from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterSearch";

export interface MarkdownHighlighterProps {
  searchValue?: string;
  localSearchValue?: string;
  setLocalSearchValue: (value: string) => void;
  codeOutput: CodeOutput;
  modeSelector: ReactNode;
  copyButton: ReactNode;
  withSearch?: boolean;
  scrollRef?: React.RefObject<HTMLDivElement>;
  onScroll?: (e: React.UIEvent<HTMLDivElement>) => void;
  maxHeight?: string;
}

const MarkdownHighlighter: React.FC<MarkdownHighlighterProps> = ({
  codeOutput,
  localSearchValue,
  searchValue,
  setLocalSearchValue,
  modeSelector,
  copyButton,
  withSearch,
  scrollRef,
  onScroll,
  maxHeight,
}) => {
  // Use scrollRef for both scroll tracking and search container
  const { searchPlugin, searchPlainText, findNext, findPrev } =
    useMarkdownSearch({
      searchValue: localSearchValue || searchValue,
      container: scrollRef?.current || null,
    });

  const markdownPreview = useMemo(() => {
    if (isNull(codeOutput.message)) return "";

    if (isStringMarkdown(codeOutput.message)) {
      return (
        <ReactMarkdown
          className={cn("prose dark:prose-invert comet-markdown")}
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
            <SyntaxHighlighterSearch
              searchValue={localSearchValue}
              onSearch={setLocalSearchValue}
              onPrev={findPrev}
              onNext={findNext}
            />
          )}
          {copyButton}
        </>
      }
    >
      <div
        ref={scrollRef}
        onScroll={onScroll}
        className={maxHeight ? "overflow-y-auto p-3" : "p-3"}
        style={maxHeight ? { maxHeight } : undefined}
      >
        {markdownPreview}
      </div>
    </SyntaxHighlighterLayout>
  );
};

export default MarkdownHighlighter;
