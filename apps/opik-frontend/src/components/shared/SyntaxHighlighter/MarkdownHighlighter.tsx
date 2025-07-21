import React, { ReactNode, useMemo, useRef } from "react";
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
}

const MarkdownHighlighter: React.FC<MarkdownHighlighterProps> = ({
  codeOutput,
  localSearchValue,
  searchValue,
  setLocalSearchValue,
  modeSelector,
  copyButton,
  withSearch,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const { searchPlugin, searchPlainText, findNext, findPrev } =
    useMarkdownSearch({
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
      <div className="p-3" ref={containerRef}>
        {markdownPreview}
      </div>
    </SyntaxHighlighterLayout>
  );
};

export default MarkdownHighlighter;
