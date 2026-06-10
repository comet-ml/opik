import { OPIK_PARENT_SPAN_ID, OPIK_TRACE_ID } from "./attributes";

/**
 * Represents distributed trace attributes for the OPIK tracing system.
 *
 * This class provides a structured way to handle distributed trace attributes,
 * specifically the trace ID and parent span ID. It exposes a method to convert
 * those attributes into a plain object suitable for use as OpenTelemetry span
 * attributes.
 */
export class OpikDistributedTraceAttributes {
  private readonly opikTraceId: string;
  private readonly opikParentSpanId?: string;

  /**
   * @param opikTraceId The unique identifier for the trace.
   * @param opikParentSpanId The identifier for the parent span in the trace,
   * or undefined if this is the root span.
   */
  constructor(opikTraceId: string, opikParentSpanId?: string) {
    this.opikTraceId = opikTraceId;
    this.opikParentSpanId = opikParentSpanId;
  }

  /**
   * Converts the distributed trace attributes into a plain object suitable
   * for OpenTelemetry `Span.setAttributes`.
   */
  asAttributes(): Record<string, string> {
    const result: Record<string, string> = {
      [OPIK_TRACE_ID]: this.opikTraceId,
    };
    if (this.opikParentSpanId !== undefined) {
      result[OPIK_PARENT_SPAN_ID] = this.opikParentSpanId;
    }
    return result;
  }
}