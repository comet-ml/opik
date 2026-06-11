import get from "lodash/get";

import { Span, Trace } from "@/types/traces";

// Namespaced metadata container the SDK integrations write categorization info into.
const METADATA_OPIK_KEY = "_opik";
const OPIK_META_CATEGORY_KEY = "category";

// SDK-provided span category values the UI collapses by default. The UI does not interpret
// why a span carries one of these — it only trusts that the SDK marked it as not worth
// showing by default. Extend this list as the SDK adds more such categories.
const HIDDEN_BY_DEFAULT_SPAN_CATEGORIES = ["internal"];

// Decision layer for "should this span be hidden by default". A rule inspects a single span
// and returns true to hide it. A span is hidden if ANY rule matches, so visibility can be
// driven by several independent signals, not only the SDK flag.
export type SpanHideRule = (span: Span | Trace) => boolean;

// The SDK explicitly tags some spans with a category the UI collapses.
const bySdkCategory: SpanHideRule = (span) => {
  const category = get(span, [
    "metadata",
    METADATA_OPIK_KEY,
    OPIK_META_CATEGORY_KEY,
  ]);
  return HIDDEN_BY_DEFAULT_SPAN_CATEGORIES.includes(category as string);
};

// Add future rules here (e.g. name/type heuristics or user preferences) — the rendering
// layer consumes only `isSpanHiddenByDefault` and does not need to change.
export const SPAN_HIDE_RULES: SpanHideRule[] = [bySdkCategory];

export const isSpanHiddenByDefault = (span: Span | Trace): boolean =>
  SPAN_HIDE_RULES.some((rule) => rule(span));
