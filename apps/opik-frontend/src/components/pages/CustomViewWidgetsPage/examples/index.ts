/**
 * Widget Examples Index
 *
 * Re-exports all widget examples for use in CustomViewWidgetsPage.
 * Each example demonstrates complex widget compositions based on Figma designs.
 *
 * Example Categories:
 * - COMPLETE TRACE VIEW: Full trace visualization with Input/Output at root
 * - THREAD VIEW: Thread with ChatMessage widgets
 * - SPAN-LEVEL VIEW: Detail view for a single span (embedded in trace views)
 * - PATTERN DEMO: Widget composition patterns (not complete views)
 */

export { simpleTraceExample } from "./simpleTraceExample";
export { toolCallSpanExample } from "./toolCallSpanExample";
export { retrievalSpanExample } from "./retrievalSpanExample";
export { traceWithToolCallsExample } from "./traceWithToolCallsExample";
export { threadOverviewExample } from "./threadOverviewExample";
export { metadataContainerExample } from "./metadataContainerExample";
export { inlineGroupingExample } from "./inlineGroupingExample";
export { containerLayoutExample } from "./containerLayoutExample";
export { imageAttachmentExample } from "./imageAttachmentExample";
export { videoAttachmentExample } from "./videoAttachmentExample";
export { audioAttachmentExample } from "./audioAttachmentExample";

// Convenience array export for iteration
import { simpleTraceExample } from "./simpleTraceExample";
import { toolCallSpanExample } from "./toolCallSpanExample";
import { retrievalSpanExample } from "./retrievalSpanExample";
import { traceWithToolCallsExample } from "./traceWithToolCallsExample";
import { threadOverviewExample } from "./threadOverviewExample";
import { metadataContainerExample } from "./metadataContainerExample";
import { inlineGroupingExample } from "./inlineGroupingExample";
import { containerLayoutExample } from "./containerLayoutExample";
import { imageAttachmentExample } from "./imageAttachmentExample";
import { videoAttachmentExample } from "./videoAttachmentExample";
import { audioAttachmentExample } from "./audioAttachmentExample";

export const allExamples = [
  simpleTraceExample,
  traceWithToolCallsExample,
  threadOverviewExample,
  toolCallSpanExample,
  retrievalSpanExample,
  metadataContainerExample,
  inlineGroupingExample,
  containerLayoutExample,
  imageAttachmentExample,
  videoAttachmentExample,
  audioAttachmentExample,
];
