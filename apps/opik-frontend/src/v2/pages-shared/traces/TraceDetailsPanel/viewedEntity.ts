import find from "lodash/find";
import { Span, Trace } from "@/types/traces";
import { QuickFilterEntity } from "@/shared/filter-chips/QuickAttributeFilterContext";

interface ViewedEntity {
  // What the data viewer renders.
  data: Trace | Span | undefined;
  // Which entity that data belongs to. Anything that acts on the attributes on
  // screen — the quick filter — has to follow this, not `spanId`.
  entity: QuickFilterEntity;
}

/**
 * Resolves what the details panel shows.
 *
 * A `spanId` from the URL can name a span that is not among the loaded ones:
 * while the list is still loading, and for good when the span belongs to
 * another trace or sits past the load limit. The panel shows the trace in both
 * cases.
 *
 * The data and the entity come back together on purpose. They were derived
 * separately once, drifted, and sent trace attributes to the Spans table.
 */
export const resolveViewedEntity = (
  spanId: string | null | undefined,
  spans: Span[] | undefined,
  trace: Trace | undefined,
): ViewedEntity => {
  const span = spanId ? find(spans ?? [], (s) => s.id === spanId) : undefined;
  return span
    ? { data: span, entity: "span" }
    : { data: trace, entity: "trace" };
};
