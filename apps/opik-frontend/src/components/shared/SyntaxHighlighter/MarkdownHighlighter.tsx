import React, { ReactNode, useMemo, useRef } from "react";
import { CodeOutput } from "@/components/shared/SyntaxHighlighter/types";
import SyntaxHighlighterLayout from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterLayout";
import { useMarkdownSearch } from "@/components/shared/SyntaxHighlighter/hooks/useMarkdownSearch";
import JsonKeyValueTable from "@/components/shared/JsonKeyValueTable/JsonKeyValueTable";
import { isStringMarkdown } from "@/lib/utils";
import ReactMarkdown from "react-markdown";
import rehypeRaw from "rehype-raw";
import remarkBreaks from "remark-breaks";
import remarkGfm from "node_modules/remark-gfm/lib";
import { isNull } from "lodash";
import SyntaxHighlighterSearch from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterSearch";

const DEFAULT_JSON_TABLE_MAX_DEPTH = 5;

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
    if (isNull(codeOutput.message)) {
      return <div className="comet-code text-muted-foreground">null</div>;
    }

    // Handle object messages - render as JSON table
    if (typeof codeOutput.message === "object" && codeOutput.message !== null) {
      return (
        <JsonKeyValueTable
          data={codeOutput.message}
          maxDepth={DEFAULT_JSON_TABLE_MAX_DEPTH}
          localStorageKey="json-table-expanded-state"
        />
      );
    }

    // Handle structured result from prettifyMessage
    if (
      typeof codeOutput.message === "object" &&
      codeOutput.message !== null &&
      "renderType" in codeOutput.message
    ) {
      const structuredResult = codeOutput.message as {
        renderType: string;
        data: unknown;
      };
      if (structuredResult.renderType === "json-table") {
        return (
          <JsonKeyValueTable
            data={structuredResult.data}
            maxDepth={DEFAULT_JSON_TABLE_MAX_DEPTH}
            localStorageKey="json-table-expanded-state"
          />
        );
      }
    }

    if (isStringMarkdown(codeOutput.message)) {
      return (
        <ReactMarkdown
          className="comet-markdown comet-markdown-highlighter prose dark:prose-invert"
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

  // Check if the content is a JSON table (not searchable)
  const isJsonTable = useMemo(() => {
    if (isNull(codeOutput.message)) return false;

    // Handle object messages - render as JSON table
    if (typeof codeOutput.message === "object" && codeOutput.message !== null) {
      return true;
    }

    // Handle structured result from prettifyMessage
    if (
      typeof codeOutput.message === "object" &&
      codeOutput.message !== null &&
      "renderType" in codeOutput.message
    ) {
      const structuredResult = codeOutput.message as {
        renderType: string;
        data: unknown;
      };
      return structuredResult.renderType === "json-table";
    }

    return false;
  }, [codeOutput.message]);

  return (
    <SyntaxHighlighterLayout
      leftHeader={modeSelector}
      rightHeader={
        <>
          {withSearch && !isJsonTable && (
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
