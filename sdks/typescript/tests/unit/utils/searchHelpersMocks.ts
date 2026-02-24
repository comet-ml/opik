import * as OpikApi from "@/rest_api/api";

/**
 * Factory functions for creating mock trace data
 */
export const createMockTrace = (
  overrides?: Partial<OpikApi.TracePublic>
): OpikApi.TracePublic => ({
  id: "trace-1",
  projectId: "project-1",
  name: "Test Trace",
  startTime: new Date("2024-01-01T00:00:00Z"),
  endTime: new Date("2024-01-01T00:01:00Z"),
  input: { prompt: "test" },
  output: { response: "result" },
  ...overrides,
});

export const createMockTraces = (count: number): OpikApi.TracePublic[] =>
  Array.from({ length: count }, (_, i) =>
    createMockTrace({
      id: `trace-${i + 1}`,
      name: `Trace ${i + 1}`,
    })
  );

export const createMockTraceWithMetadata = (
  metadata: Record<string, unknown>
): OpikApi.TracePublic =>
  createMockTrace({
    metadata,
  });

export const createMockTraceWithFeedback = (
  feedbackScores: OpikApi.FeedbackScorePublic[]
): OpikApi.TracePublic =>
  createMockTrace({
    feedbackScores,
  });

export const createMockThread = (
  overrides?: Partial<OpikApi.TraceThread>
): OpikApi.TraceThread => ({
  id: "thread-1",
  projectId: "project-1",
  threadModelId: "thread-model-1",
  startTime: new Date("2024-01-01T00:00:00Z"),
  endTime: new Date("2024-01-01T00:10:00Z"),
  duration: 600,
  status: "active",
  numberOfMessages: 5,
  totalEstimatedCost: 0.05,
  ...overrides,
});

export const createMockThreads = (count: number): OpikApi.TraceThread[] =>
  Array.from({ length: count }, (_, i) =>
    createMockThread({
      id: `thread-${i + 1}`,
      threadModelId: `thread-model-${i + 1}`,
    })
  );

export const createMockSpan = (
  overrides?: Partial<OpikApi.SpanPublic>
): OpikApi.SpanPublic => ({
  id: "span-1",
  projectId: "project-1",
  traceId: "trace-1",
  name: "Test Span",
  type: "llm",
  startTime: new Date("2024-01-01T00:00:00Z"),
  endTime: new Date("2024-01-01T00:00:10Z"),
  input: { prompt: "test" },
  output: { response: "result" },
  ...overrides,
});

export const createMockSpans = (count: number): OpikApi.SpanPublic[] =>
  Array.from({ length: count }, (_, i) =>
    createMockSpan({
      id: `span-${i + 1}`,
      name: `Span ${i + 1}`,
    })
  );
