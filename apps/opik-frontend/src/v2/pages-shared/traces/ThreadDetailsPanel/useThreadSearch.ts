import { useCallback, useEffect, useState } from "react";
import isObject from "lodash/isObject";

import { Trace } from "@/types/traces";
import { prettifyMessage } from "@/lib/traces";
import { toString } from "@/lib/utils";

const MATCH_HIGHLIGHT = "thread-search-match";
const CURRENT_MATCH_HIGHLIGHT = "thread-search-current-match";
// Only message bodies are searched, so the per-message action buttons ("Trace", "Tool calls")
// never count as matches.
const MESSAGE_CONTENT_SELECTOR = "[data-message-content]";

const messageText = (message: unknown) =>
  isObject(message) ? JSON.stringify(message) : toString(message as string);

// Matches against the prettified message, the same text Messages renders, so a kept turn
// always has something to highlight.
export const traceMatchesSearch = (trace: Trace, search: string): boolean => {
  const needle = search.toLowerCase();

  return [
    prettifyMessage(trace.input).message,
    prettifyMessage(trace.output, { type: "output" }).message,
  ].some((message) => messageText(message).toLowerCase().includes(needle));
};

export const findTextRanges = (roots: Element[], search: string): Range[] => {
  const needle = search.toLowerCase();
  const ranges: Range[] = [];

  roots.forEach((root) => {
    const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);

    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = (node.nodeValue ?? "").toLowerCase();

      for (
        let index = text.indexOf(needle);
        index !== -1;
        index = text.indexOf(needle, index + needle.length)
      ) {
        const range = document.createRange();
        range.setStart(node, index);
        range.setEnd(node, index + needle.length);
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
      container && search?.trim()
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
