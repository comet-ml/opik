import { visit } from "unist-util-visit";
import React from "react";
import {
  CodeNode,
  HtmlNode,
  InlineCodeNode,
  TextNode,
  MatchIndex,
  VisitorNode,
} from "@/components/shared/SyntaxHighlighter/types";
import { createSearchRegex } from "@/components/shared/SyntaxHighlighter/utils";
import {
  SEARCH_CURRENT_HIGHLIGHT_COLOR,
  SEARCH_HIGHLIGHT_COLOR,
} from "@/components/shared/SyntaxHighlighter/constants";

const createHighlightMarkup = (
  match: string,
  matchIndex: number,
  currentMatchIndex: number,
): string => {
  const isCurrentMatch = currentMatchIndex === matchIndex;
  const highlightClass = isCurrentMatch
    ? SEARCH_CURRENT_HIGHLIGHT_COLOR
    : SEARCH_HIGHLIGHT_COLOR;

  return `<mark class="${highlightClass}" data-match-index="${matchIndex}" data-current-match-index="${currentMatchIndex}">${match}</mark>`;
};

const createTextNodeVisitor = (
  regex: RegExp,
  matchIndexRef: MatchIndex,
  currentMatchIndex: number,
) => {
  return (node: TextNode) => {
    const { value } = node;
    if (regex.test(value)) {
      const highlightedValue = value.replace(regex, (match: string) =>
        createHighlightMarkup(match, matchIndexRef.value++, currentMatchIndex),
      );
      (node as unknown as HtmlNode).type = "html";
      (node as unknown as HtmlNode).value = highlightedValue;
    }
  };
};

const createInlineCodeNodeVisitor = (
  regex: RegExp,
  matchIndex: MatchIndex,
  currentMatchIndex: number,
) => {
  return (node: InlineCodeNode) => {
    const { value } = node;
    if (regex.test(value)) {
      const highlightedValue = value.replace(regex, (match: string) =>
        createHighlightMarkup(match, matchIndex.value++, currentMatchIndex),
      );
      (node as unknown as HtmlNode).type = "html";
      (node as unknown as HtmlNode).value = `<code>${highlightedValue}</code>`;
    }
  };
};

const createCodeBlockNodeVisitor = (
  regex: RegExp,
  matchIndex: MatchIndex,
  currentMatchIndex: number,
) => {
  return (node: CodeNode) => {
    const { value } = node;
    if (regex.test(value)) {
      const highlightedValue = value.replace(regex, (match: string) =>
        createHighlightMarkup(match, matchIndex.value++, currentMatchIndex),
      );
      (node as unknown as HtmlNode).type = "html";
      (node as unknown as HtmlNode).value =
        `<pre><code>${highlightedValue}</code></pre>`;
    }
  };
};

export const createRemarkSearchPlugin = (
  searchTerm: string,
  matchIndex: MatchIndex,
  currentMatchIndex: number,
) => {
  return (tree: VisitorNode) => {
    const regex = createSearchRegex(searchTerm);

    visit(
      tree,
      "text",
      createTextNodeVisitor(regex, matchIndex, currentMatchIndex),
    );
    visit(
      tree,
      "inlineCode",
      createInlineCodeNodeVisitor(regex, matchIndex, currentMatchIndex),
    );
    visit(
      tree,
      "code",
      createCodeBlockNodeVisitor(regex, matchIndex, currentMatchIndex),
    );
  };
};

export const highlightPlainText = (
  text: string,
  searchTerm: string,
  matchIndex: MatchIndex,
  currentMatchIndex: number,
): React.ReactNode[] => {
  const regex = createSearchRegex(searchTerm);
  const parts = text.split(regex);

  return parts.reduce<React.ReactNode[]>((elements, part, index) => {
    if (part === "") return elements;

    if (!regex.test(part)) {
      elements.push(part);
      return elements;
    }

    const currentIndex = matchIndex.value++;
    const isCurrentMatch = currentMatchIndex === currentIndex;
    const highlightClass = isCurrentMatch
      ? SEARCH_CURRENT_HIGHLIGHT_COLOR
      : SEARCH_HIGHLIGHT_COLOR;

    elements.push(
      React.createElement(
        "mark",
        {
          key: `highlight-${index}`,
          className: highlightClass,
          "data-match-index": currentIndex,
          "data-current-match-index": currentMatchIndex,
        },
        part,
      ),
    );

    return elements;
  }, []);
};
