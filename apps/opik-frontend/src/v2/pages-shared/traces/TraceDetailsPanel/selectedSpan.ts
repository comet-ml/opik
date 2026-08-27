import find from "lodash/find";
import { Span } from "@/types/traces";

/**
 * The span the details panel shows, or undefined when it shows the trace.
 *
 * A `spanId` from the URL can name a span that is not among the loaded ones —
 * while the list is still loading, and for good when the span belongs to
 * another trace or sits past the load limit. The panel falls back to the trace
 * in both cases, so anything that follows the panel must resolve through here
 * rather than test `spanId` on its own.
 */
export const findSelectedSpan = (
  spanId: string | null | undefined,
  spans: Span[] | undefined,
): Span | undefined =>
  spanId ? find(spans ?? [], (span) => span.id === spanId) : undefined;
