import { useCallback, useEffect, useState } from "react";
import isObject from "lodash/isObject";

import { Trace } from "@/types/traces";
import { prettifyMessage } from "@/lib/traces";
import { toString } from "@/lib/utils";
import { createSearchRegex } from "@/shared/SyntaxHighlighter/utils";

const MATCH_HIGHLIGHT = "thread-search-match";
const CURRENT_MATCH_HIGHLIGHT = "thread-search-current-match";
// Only message bodies are searched, so the per-message action buttons ("Trace", "Tool calls")
// never count as matches.
const MESSAGE_CONTENT_SELECTOR = "[data-message-content]";

const messageText = (message: unknown) =>
  isObject(message) ? JSON.stringify(message) : toString(message as string);

// Matches against the prettified message, the same text Messages renders, with the same
// regex findTextRanges uses, so a kept turn always has something to highlight.
export const traceMatchesSearch = (trace: Trace, search: string): boolean => {
  const regex = createSearchRegex(search);

  return [
    prettifyMessage(trace.input).message,
    prettifyMessage(trace.output, { type: "output" }).message,
  ].some((message) => messageText(message).search(regex) !== -1);
};

export const findTextRanges = (roots: Element[], search: string): Range[] => {
  if (!search.trim()) return [];

  // Matching the original text (not a lowercased copy) keeps offsets valid: lowercasing
  // can change string length (e.g. "İ"), which would misplace or overflow the ranges.
  const regex = createSearchRegex(search);
  const ranges: Range[] = [];

  roots.forEach((root) => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);

    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      for (const match of (node.nodeValue ?? "").matchAll(regex)) {
        const range = document.createRange();
        range.setStart(node, match.index);
        range.setEnd(node, match.index + match[0].length);
        ranges.push(range);
      }
    }
  });

  return ranges;
};

// Collapsible Markdown headings render as closed <details>; a match inside one is invisible
// until every enclosing <details> is opened.
const revealRange = (range: Range) => {
  let details = range.startContainer.parentElement?.closest("details");

  while (details) {
    details.open = true;
    details = details.parentElement?.closest("details");
  }
};

const getHighlightRegistry = () =>
  typeof CSS !== "undefined" &&
  "highlights" in CSS &&
  typeof Highlight !== "undefined"
    ? CSS.highlights
    : undefined;

type UseThreadSearchParams = {
  container: HTMLElement | null;
  search?: string;
  traces: Trace[];
};

export const useThreadSearch = ({
  container,
  search,
  traces,
}: UseThreadSearchParams) => {
  const [ranges, setRanges] = useState<Range[]>([]);
  const [currentIndex, setCurrentIndex] = useState(0);

  // `traces` is a dependency because the rendered messages change with it, even when the
  // search does not.
  useEffect(() => {
    setRanges(
      container && search
        ? findTextRanges(
            Array.from(container.querySelectorAll(MESSAGE_CONTENT_SELECTOR)),
            search,
          )
        : [],
    );
    setCurrentIndex(0);
  }, [container, search, traces]);

  useEffect(() => {
    const current = ranges[currentIndex];
    if (!current) return;

    revealRange(current);
    current.startContainer.parentElement?.scrollIntoView({ block: "center" });

    const registry = getHighlightRegistry();
    if (!registry) return;

    registry.set(MATCH_HIGHLIGHT, new Highlight(...ranges));
    registry.set(CURRENT_MATCH_HIGHLIGHT, new Highlight(current));

    return () => {
      registry.delete(MATCH_HIGHLIGHT);
      registry.delete(CURRENT_MATCH_HIGHLIGHT);
    };
  }, [ranges, currentIndex]);

  const findNext = useCallback(() => {
    if (!ranges.length) return;
    setCurrentIndex((index) => (index + 1) % ranges.length);
  }, [ranges.length]);

  const findPrev = useCallback(() => {
    if (!ranges.length) return;
    setCurrentIndex((index) => (index - 1 + ranges.length) % ranges.length);
  }, [ranges.length]);

  return { findNext, findPrev };
};
