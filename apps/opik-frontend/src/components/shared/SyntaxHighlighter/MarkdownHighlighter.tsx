import React, { ReactNode, useMemo } from "react";
import { CodeOutput } from "@/components/shared/SyntaxHighlighter/types";
import SyntaxHighlighterLayout from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterLayout";
import { useMarkdownSearch } from "@/components/shared/SyntaxHighlighter/hooks/useMarkdownSearch";
import JsonKeyValueTable from "@/components/shared/JsonKeyValueTable/JsonKeyValueTable";
import { isStringMarkdown } from "@/lib/utils";
import { makeHeadingsCollapsible } from "@/lib/remarkCollapsibleHeadings";
import ReactMarkdown from "react-markdown";
import rehypeRaw from "rehype-raw";
import remarkBreaks from "remark-breaks";
import remarkGfm from "node_modules/remark-gfm/lib";
import { isNull } from "lodash";
import SyntaxHighlighterSearch from "@/components/shared/SyntaxHighlighter/SyntaxHighlighterSearch";
import { ExpandedState } from "@tanstack/react-table";

const DEFAULT_JSON_TABLE_MAX_DEPTH = 5;

export interface MarkdownHighlighterProps {
  searchValue?: string;
  localSearchValue?: string;
  setLocalSearchValue: (value: string) => void;
  codeOutput: CodeOutput;
  modeSelector: ReactNode;
  copyButton: ReactNode;
  withSearch?: boolean;
  controlledExpanded?: ExpandedState;
  onExpandedChange?: (
    updaterOrValue: ExpandedState | ((old: ExpandedState) => ExpandedState),
  ) => void;
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
  controlledExpanded,
  onExpandedChange,
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
    if (isNull(codeOutput.message)) {
      return <div className="comet-code text-muted-foreground">null</div>;
    }

    // Handle object messages - render as JSON table
    if (typeof codeOutput.message === "object" && codeOutput.message !== null) {
      return (
        <JsonKeyValueTable
          data={codeOutput.message}
          maxDepth={DEFAULT_JSON_TABLE_MAX_DEPTH}
          localStorageKey={
            controlledExpanded ? undefined : "json-table-expanded-state"
          }
          controlledExpanded={controlledExpanded}
          onExpandedChange={onExpandedChange}
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
            localStorageKey={
              controlledExpanded ? undefined : "json-table-expanded-state"
            }
            controlledExpanded={controlledExpanded}
            onExpandedChange={onExpandedChange}
          />
        );
      }
    }

    if (isStringMarkdown(codeOutput.message)) {
      // Transform the markdown to make headings collapsible
      const collapsibleMarkdown = makeHeadingsCollapsible(codeOutput.message, {
        defaultOpen: false,
        className: "collapsible-heading",
        summaryClassName: "collapsible-heading-summary",
        contentClassName: "collapsible-heading-content",
      });

      return (
        <ReactMarkdown
          className="comet-markdown comet-markdown-highlighter prose dark:prose-invert"
          remarkPlugins={[remarkBreaks, remarkGfm, searchPlugin]}
          rehypePlugins={[rehypeRaw]}
        >
          {collapsibleMarkdown}
        </ReactMarkdown>
      );
    }

    return (
      <div className="comet-markdown whitespace-pre-wrap">
        {searchPlainText(codeOutput.message)}
      </div>
    );
  }, [
    codeOutput.message,
    searchPlugin,
    searchPlainText,
    controlledExpanded,
    onExpandedChange,
  ]);

  // Check if the content is a JSON table (not searchable)
  const isJsonTable = useMemo(() => {
    if (isNull(codeOutput.message)) return false;

    // Handle object messages - render as JSON table
    if (typeof codeOutput.message === "object" && codeOutput.message !== null) {
      return true;
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
