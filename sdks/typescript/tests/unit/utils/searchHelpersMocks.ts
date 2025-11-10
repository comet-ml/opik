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
