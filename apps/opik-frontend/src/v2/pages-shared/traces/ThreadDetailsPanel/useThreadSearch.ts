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

// The strings Messages renders for a message. JsonView shows a structured message's keys and
// values unescaped, so its stringified JSON would miss text like `C:\Users` or `say "hi"`.
const messageTexts = (message: unknown): string[] => {
  if (Array.isArray(message)) return message.flatMap(messageTexts);
  if (isObject(message)) {
    return Object.entries(message).flatMap(([key, value]) => [
      key,
      ...messageTexts(value),
    ]);
  }
  return [toString(message as string)];
};

// Matches against the prettified message, the same text Messages renders, with the same
// regex findTextRanges uses, so a kept turn always has something to highlight.
export const traceMatchesSearch = (trace: Trace, search: string): boolean => {
  if (!search.trim()) return false;

  const regex = createSearchRegex(search);

  return [
    prettifyMessage(trace.input).message,
    prettifyMessage(trace.output, { type: "output" }).message,
  ]
    .flatMap(messageTexts)
    .some((text) => text.search(regex) !== -1);
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

const getScrollParent = (element: Element | null): Element | null => {
  for (let node = element; node; node = node.parentElement) {
    const { overflowY } = getComputedStyle(node);
    if (
      (overflowY === "auto" || overflowY === "scroll") &&
      node.scrollHeight > node.clientHeight
    ) {
      return node;
    }
  }
  return null;
};

// Centers the match itself, not its element: a long plain-text message is a single text node,
// so scrolling its element would leave every match in it at the same position.
export const scrollRangeIntoView = (range: Range) => {
  const element = range.startContainer.parentElement;
  const scroller = getScrollParent(element);

  if (!scroller) {
    element?.scrollIntoView({ block: "center" });
    return;
  }

  const rect = range.getBoundingClientRect();
  const box = scroller.getBoundingClientRect();
  scroller.scrollTo({
    top:
      scroller.scrollTop +
      rect.top -
      box.top -
      (scroller.clientHeight - rect.height) / 2,
  });
};

// Adds ranges one at a time: passing them as constructor arguments overflows the call stack
// once a short search matches a few hundred thousand times.
export const createMatchHighlight = (ranges: Range[]) => {
  const highlight = new Highlight();
  ranges.forEach((range) => highlight.add(range));
  return highlight;
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

  // Kept apart from the current match so ↑/↓ doesn't rebuild the highlight of every match.
  useEffect(() => {
    const registry = getHighlightRegistry();
    if (!registry || !ranges.length) return;

    registry.set(MATCH_HIGHLIGHT, createMatchHighlight(ranges));

    return () => {
      registry.delete(MATCH_HIGHLIGHT);
    };
  }, [ranges]);

  useEffect(() => {
    const current = ranges[currentIndex];
    if (!current) return;

    revealRange(current);
    scrollRangeIntoView(current);

    const registry = getHighlightRegistry();
    if (!registry) return;

    registry.set(CURRENT_MATCH_HIGHLIGHT, new Highlight(current));

    return () => {
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
