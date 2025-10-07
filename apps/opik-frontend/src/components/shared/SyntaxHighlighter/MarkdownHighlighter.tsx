import React, { ReactNode, useMemo, useRef } from "react";
import { CodeOutput } from "@/components/shared/SyntaxHighlighter/types";
import SyntaxHighlighterLayout from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterLayout";
import { useMarkdownSearch } from "@/components/shared/SyntaxHighlighter/hooks/useMarkdownSearch";
import JsonKeyValueTable from "@/components/shared/JsonKeyValueTable/JsonKeyValueTable";
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

    // Handle object messages - render as JSON table
    if (typeof codeOutput.message === "object" && codeOutput.message !== null) {
      return <JsonKeyValueTable data={codeOutput.message} maxDepth={3} />;
    }

    // Check if message contains JSON table marker
    const isJsonTable =
      typeof codeOutput.message === "string" &&
      codeOutput.message.startsWith("__JSON_TABLE__:");

    if (isJsonTable) {
      try {
        const jsonData = JSON.parse(
          codeOutput.message.substring("__JSON_TABLE__:".length),
        );
        return <JsonKeyValueTable data={jsonData} maxDepth={3} />;
      } catch {
        // If parsing fails, fall back to regular display
      }
    }

    if (isStringMarkdown(codeOutput.message)) {
      return (
        <ReactMarkdown
          className={cn(
            "prose dark:prose-invert comet-markdown comet-markdown-highlighter",
          )}
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
