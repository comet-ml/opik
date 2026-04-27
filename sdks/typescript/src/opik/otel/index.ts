export { OpikDistributedTraceAttributes } from "./OpikDistributedTraceAttributes";
export {
  OPIK_TRACE_ID_HEADER,
  OPIK_PARENT_SPAN_ID_HEADER,
  attachToParent,
  extractOpikDistributedTraceAttributes,
} from "./distributedTrace";
export type {
  HttpHeadersLike,
  OpenTelemetrySpanLike,
} from "./distributedTrace";