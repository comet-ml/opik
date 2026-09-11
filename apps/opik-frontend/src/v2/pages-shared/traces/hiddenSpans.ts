import { useMemo } from "react";
import useLocalStorageState from "use-local-storage-state";

import { Span, Trace } from "@/types/traces";
import { isSpanHiddenByDefault } from "@/v2/pages-shared/traces/spanVisibility";

// Per-user preference (localStorage): whether spans hidden by default are collapsed.
const TRACE_HIDE_SPANS_KEY = "trace-hide-spans";

export const hasHiddenSpans = (spans: Array<Span | Trace>): boolean =>
  spans.some(isSpanHiddenByDefault);

/**
 * Returns a new span list with hidden-by-default spans removed and every remaining span
 * re-pointed to its nearest visible ancestor (empty `parent_span_id` when that ancestor is
 * the trace root). The result is a drop-in replacement for the original spans — the tree
 * viewer builds the tree from `parent_span_id` as usual. The ancestor walk-up is memoized,
 * so the transform is ~O(n) even for deep chains of hidden spans.
 */
export const excludeHiddenSpans = (spans: Span[]): Span[] => {
  const spanById = new Map<string, Span>();
  spans.forEach((span) => spanById.set(span.id, span));

  const resolvedParent = new Map<string, string>();

  const resolveVisibleParent = (parentId: string): string => {
    if (!parentId) return "";

    const cached = resolvedParent.get(parentId);
    if (cached !== undefined) return cached;

    const parent = spanById.get(parentId);
    // Parent not in the loaded set — treat as a trace-root child.
    if (!parent) return "";

    const result = isSpanHiddenByDefault(parent)
      ? resolveVisibleParent(parent.parent_span_id)
      : parentId;

    resolvedParent.set(parentId, result);
    return result;
  };

  return spans
    .filter((span) => !isSpanHiddenByDefault(span))
    .map((span) => ({
      ...span,
      parent_span_id: resolveVisibleParent(span.parent_span_id),
    }));
};

// Shared, per-user collapse preference. Multiple components reading this stay in sync via
// localStorage, so the toggle and the rendering panel do not need to thread state.
export const useHideSpansPreference = (): [
  boolean,
  (hidden: boolean) => void,
] => {
  const [hidden = true, setHidden] = useLocalStorageState<boolean>(
    TRACE_HIDE_SPANS_KEY,
    { defaultValue: true },
  );
  return [hidden, setHidden];
};

// The spans the panel should actually render, given the current collapse preference.
export const useVisibleSpans = (spans: Span[]): Span[] => {
  const [hidden] = useHideSpansPreference();
  return useMemo(
    () => (hidden ? excludeHiddenSpans(spans) : spans),
    [hidden, spans],
  );
};
