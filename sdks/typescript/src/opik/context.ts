import { getTrackContext } from "@/decorators/track";

/**
 * HTTP header keys carrying Opik distributed trace context across service
 * boundaries. They are intentionally lowercase to match the canonical form
 * `Headers#get` returns and the way `node:http` exposes incoming headers.
 */
export const OPIK_TRACE_ID_HEADER = "opik_trace_id";
export const OPIK_PARENT_SPAN_ID_HEADER = "opik_parent_span_id";

export interface DistributedTraceHeaders {
  [OPIK_TRACE_ID_HEADER]: string;
  [OPIK_PARENT_SPAN_ID_HEADER]: string;
}

/**
 * Returns the Opik distributed-trace HTTP headers describing the currently
 * active trace and span. Intended to be called from inside a function
 * wrapped with `track()` (or any code running within a `trackStorage`
 * context); returns `null` when called outside an active trace context.
 */
export function getDistributedTraceHeaders(): DistributedTraceHeaders | null {
  const ctx = getTrackContext();
  if (!ctx) {
    return null;
  }
  return {
    [OPIK_TRACE_ID_HEADER]: ctx.trace.data.id,
    [OPIK_PARENT_SPAN_ID_HEADER]: ctx.span.data.id,
  };
}
