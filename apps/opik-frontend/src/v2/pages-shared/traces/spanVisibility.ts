import get from "lodash/get";

import { Span, Trace } from "@/types/traces";

// Namespaced metadata container the SDK integrations write categorization info into.
const METADATA_OPIK_KEY = "_opik";
const OPIK_META_IS_INTERNAL_KEY = "is_internal";

// Decision layer for "should this span be hidden by default". A rule inspects a single span
// and returns true to hide it. A span is hidden if ANY rule matches, so visibility can be
// driven by several independent signals, not only the SDK flag.
export type SpanHideRule = (span: Span | Trace) => boolean;

// The SDK explicitly flags framework-plumbing spans the UI collapses. The UI does not
// interpret why a span is internal — it only trusts the SDK's flag.
const bySdkInternalFlag: SpanHideRule = (span) => {
  const isInternal = get(span, [
    "metadata",
    METADATA_OPIK_KEY,
    OPIK_META_IS_INTERNAL_KEY,
  ]);
  return isInternal === true;
};

// Add future rules here (e.g. name/type heuristics or user preferences) — the rendering
// layer consumes only `isSpanHiddenByDefault` and does not need to change.
export const SPAN_HIDE_RULES: SpanHideRule[] = [bySdkInternalFlag];

export const isSpanHiddenByDefault = (span: Span | Trace): boolean =>
  SPAN_HIDE_RULES.some((rule) => rule(span));
