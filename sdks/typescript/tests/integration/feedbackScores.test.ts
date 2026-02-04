/**
 * Integration tests for logTracesFeedbackScores and logSpansFeedbackScores.
 *
 * These tests verify that feedback scores can be logged to traces and spans
 * via the batch queue system, AND that they are actually persisted to the backend.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import type { FeedbackScorePublic } from "@/rest_api/api";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";

const shouldRunApiTests = shouldRunIntegrationTests();

// Test constants
const TEST_TIMEOUT = 60000;
const EXTENDED_TIMEOUT = 90000;
const POLL_TIMEOUT_SECONDS = 30;

// Helper type for expected feedback scores
interface ExpectedFeedbackScore {
  name: string;
  value: number;
  categoryName?: string;
  reason?: string;
}

/**
 * Parameterized verification helper for feedback scores.
 * Works for both traces and spans based on entityType.
 */
async function verifyFeedbackScores(
  client: Opik,
  entityType: "trace" | "span",
  entityId: string,
  expectedScores: ExpectedFeedbackScore[],
  timeoutMs: number = POLL_TIMEOUT_SECONDS * 1000
): Promise<void> {
  const fetchEntity =
    entityType === "trace"
      ? () => client.api.traces.getTraceById(entityId)
      : () => client.api.spans.getSpanById(entityId);

  // Poll until feedback scores are persisted
  const startTime = Date.now();
  let actualScores: FeedbackScorePublic[] = [];

  while (Date.now() - startTime < timeoutMs) {
    try {
      const entity = await fetchEntity();
      actualScores = entity.feedbackScores ?? [];
      if (actualScores.length >= expectedScores.length) {
        break;
      }
    } catch {
      // Entity not yet available, continue polling
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }

  if (actualScores.length < expectedScores.length) {
    throw new Error(
      `Timeout waiting for ${entityType} ${entityId} to have ${expectedScores.length} feedback scores (found ${actualScores.length})`
    );
  }

  // Verify each expected score is present with correct values
  for (const expected of expectedScores) {
    const actual = actualScores.find((s) => s.name === expected.name);
    expect(actual, `Score '${expected.name}' should exist`).toBeDefined();
    expect(actual!.value).toBeCloseTo(expected.value, 4);

    if (expected.categoryName !== undefined) {
      expect(actual!.categoryName).toBe(expected.categoryName);
    }
    if (expected.reason !== undefined) {
      expect(actual!.reason?.trim()).toBe(expected.reason);
    }
  }
}

describe.skipIf(!shouldRunApiTests)(
  "Feedback Scores Integration Tests",
  () => {
    let client: Opik;
    const testProjectName = `test-feedback-scores-${Date.now()}`;

    beforeAll(() => {
      console.log(getIntegrationTestStatus());

      if (!shouldRunApiTests) {
        return;
      }

      client = new Opik({ projectName: testProjectName });
    });

    afterAll(async () => {
      if (!client) {
        return;
      }

      await client.flush();
    });

    /**
     * Helper to create a test trace with automatic end and flush
     */
    const createTestTrace = async (params: {
      name: string;
      input?: Record<string, unknown>;
      output?: Record<string, unknown>;
      metadata?: Record<string, unknown>;
    }) => {
      const trace = client.trace({
        name: params.name,
        input: params.input ?? { test: "data" },
        metadata: params.metadata,
      });

      if (params.output) {
        trace.update({ output: params.output });
      }

      trace.end();
      await client.flush();

      // Wait for trace to be indexed
      await client.searchTraces({
        projectName: testProjectName,
        filterString: `name = "${params.name}"`,
        waitForAtLeast: 1,
        waitForTimeout: POLL_TIMEOUT_SECONDS,
      });

      return trace;
    };

    /**
     * Helper to create a test span within a trace
     */
    const createTestSpan = async (params: {
      traceName: string;
      spanName: string;
      input?: Record<string, unknown>;
      output?: Record<string, unknown>;
    }) => {
      const trace = client.trace({
        name: params.traceName,
        input: { test: "parent-trace" },
      });

      const span = trace.span({
        name: params.spanName,
        input: params.input ?? { step: 1 },
      });

      if (params.output) {
        span.update({ output: params.output });
      }

      span.end();
      trace.update({ output: { completed: true } });
      trace.end();

      await client.flush();

      // Wait for trace to be indexed
      await client.searchTraces({
        projectName: testProjectName,
        filterString: `name = "${params.traceName}"`,
        waitForAtLeast: 1,
        waitForTimeout: POLL_TIMEOUT_SECONDS,
      });

      return { trace, span };
    };

    describe("Trace Feedback Scores", () => {
      it(
        "logs and persists trace feedback scores",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `test-trace-feedback-${timestamp}`,
            input: { question: "What is 2+2?" },
            output: { answer: "4" },
          });

          const expectedScores: ExpectedFeedbackScore[] = [
            { name: "quality", value: 0.9, reason: "Good answer" },
            { name: "accuracy", value: 1.0 },
          ];

          client.logTracesFeedbackScores([
            {
              id: trace.data.id,
              name: "quality",
              value: 0.9,
              reason: "Good answer",
            },
            { id: trace.data.id, name: "accuracy", value: 1.0 },
          ]);

          await client.flush();

          await verifyFeedbackScores(
            client,
            "trace",
            trace.data.id,
            expectedScores
          );
        },
        TEST_TIMEOUT
      );

      it(
        "handles scores with all optional fields",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `test-trace-full-options-${timestamp}`,
            input: { data: "test" },
            output: { result: "success" },
          });

          const expectedScores: ExpectedFeedbackScore[] = [
            {
              name: "comprehensive-test-score",
              value: 0.95,
              categoryName: "excellent",
              reason: "Comprehensive and accurate response",
            },
          ];

          client.logTracesFeedbackScores([
            {
              id: trace.data.id,
              name: "comprehensive-test-score",
              value: 0.95,
              categoryName: "excellent",
              reason: "Comprehensive and accurate response",
              projectName: testProjectName,
            },
          ]);

          await client.flush();

          await verifyFeedbackScores(
            client,
            "trace",
            trace.data.id,
            expectedScores
          );
        },
        TEST_TIMEOUT
      );
    });

    describe("Span Feedback Scores", () => {
      it(
        "logs and persists span feedback scores",
        async () => {
          const timestamp = Date.now();
          const { span } = await createTestSpan({
            traceName: `test-trace-span-feedback-${timestamp}`,
            spanName: "processing-span-feedback",
            input: { step: 1 },
            output: { result: "processed" },
          });

          const expectedScores: ExpectedFeedbackScore[] = [
            { name: "efficiency", value: 0.85, reason: "Fast processing" },
          ];

          client.logSpansFeedbackScores([
            {
              id: span.data.id,
              name: "efficiency",
              value: 0.85,
              reason: "Fast processing",
            },
          ]);

          await client.flush();

          await verifyFeedbackScores(
            client,
            "span",
            span.data.id,
            expectedScores
          );
        },
        TEST_TIMEOUT
      );
    });

    describe("Batch Operations", () => {
      it(
        "batches multiple trace scores and persists all",
        async () => {
          const timestamp = Date.now();

          // Create multiple traces
          const trace1 = await createTestTrace({
            name: `batch-feedback-trace-1-${timestamp}`,
            input: { index: 1 },
            output: { result: "output-1" },
          });

          const trace2 = await createTestTrace({
            name: `batch-feedback-trace-2-${timestamp}`,
            input: { index: 2 },
            output: { result: "output-2" },
          });

          const trace3 = await createTestTrace({
            name: `batch-feedback-trace-3-${timestamp}`,
            input: { index: 3 },
            output: { result: "output-3" },
          });

          // Log feedback scores to all traces in a single batch call
          client.logTracesFeedbackScores([
            { id: trace1.data.id, name: "batch-quality", value: 0.7 },
            { id: trace2.data.id, name: "batch-quality", value: 0.8 },
            { id: trace3.data.id, name: "batch-quality", value: 0.9 },
          ]);

          await client.flush();

          // Verify all 3 traces have their respective scores
          await verifyFeedbackScores(client, "trace", trace1.data.id, [
            { name: "batch-quality", value: 0.7 },
          ]);
          await verifyFeedbackScores(client, "trace", trace2.data.id, [
            { name: "batch-quality", value: 0.8 },
          ]);
          await verifyFeedbackScores(client, "trace", trace3.data.id, [
            { name: "batch-quality", value: 0.9 },
          ]);
        },
        EXTENDED_TIMEOUT
      );
    });

    describe("Real-World Annotation Workflow", () => {
      it(
        "simulates human annotator reviewing LLM responses",
        async () => {
          const timestamp = Date.now();

          // Simulate an LLM chat application with multiple user interactions
          const conversations = [
            {
              input: { user: "What's the capital of France?" },
              output: { assistant: "The capital of France is Paris." },
            },
            {
              input: { user: "Explain quantum computing in simple terms" },
              output: {
                assistant:
                  "Quantum computing uses quantum bits that can be 0 and 1 simultaneously...",
              },
            },
            {
              input: { user: "Write a haiku about coding" },
              output: {
                assistant:
                  "Silent keystrokes flow\nBugs emerge then fade away\nCode blooms like spring",
              },
            },
          ];

          // Create traces for each conversation
          const traceIds: string[] = [];
          for (let i = 0; i < conversations.length; i++) {
            const trace = client.trace({
              name: `llm-chat-${timestamp}-${i}`,
              input: conversations[i].input,
              output: conversations[i].output,
              metadata: { model: "gpt-4", temperature: 0.7 },
            });
            trace.end();
            traceIds.push(trace.data.id);
          }

          await client.flush();

          // Wait for all traces to be available
          await client.searchTraces({
            projectName: testProjectName,
            filterString: `name contains "llm-chat-${timestamp}"`,
            waitForAtLeast: 3,
            waitForTimeout: POLL_TIMEOUT_SECONDS,
          });

          // Simulate human annotator reviewing and scoring responses
          client.logTracesFeedbackScores([
            // First response: factually correct
            { id: traceIds[0], name: "factual_accuracy", value: 1.0 },
            { id: traceIds[0], name: "helpfulness", value: 0.9 },
            // Second response: good but could be simpler
            { id: traceIds[1], name: "factual_accuracy", value: 0.8 },
            {
              id: traceIds[1],
              name: "helpfulness",
              value: 0.7,
              reason: "Could be simplified further",
            },
            // Third response: creative but questionable quality
            {
              id: traceIds[2],
              name: "creativity",
              value: 0.9,
              categoryName: "high",
            },
            {
              id: traceIds[2],
              name: "helpfulness",
              value: 0.6,
              reason: "Haiku structure is correct but meaning is generic",
            },
          ]);
          await client.flush();

          // Verify annotation scores are attached to correct traces
          await verifyFeedbackScores(client, "trace", traceIds[0], [
            { name: "factual_accuracy", value: 1.0 },
            { name: "helpfulness", value: 0.9 },
          ]);

          await verifyFeedbackScores(client, "trace", traceIds[1], [
            { name: "factual_accuracy", value: 0.8 },
            {
              name: "helpfulness",
              value: 0.7,
              reason: "Could be simplified further",
            },
          ]);

          await verifyFeedbackScores(client, "trace", traceIds[2], [
            { name: "creativity", value: 0.9, categoryName: "high" },
            {
              name: "helpfulness",
              value: 0.6,
              reason: "Haiku structure is correct but meaning is generic",
            },
          ]);
        },
        EXTENDED_TIMEOUT
      );
    });

    describe("Edge Cases", () => {
      it(
        "handles decimal score values correctly",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `test-decimal-score-${timestamp}`,
            input: { test: "decimal" },
            output: { result: "ok" },
          });

          client.logTracesFeedbackScores([
            { id: trace.data.id, name: "precision-test-1", value: 0.123456 },
            { id: trace.data.id, name: "precision-test-2", value: 0.999999 },
            { id: trace.data.id, name: "precision-test-3", value: 0.0 },
            { id: trace.data.id, name: "precision-test-4", value: 1.0 },
          ]);
          await client.flush();

          await verifyFeedbackScores(client, "trace", trace.data.id, [
            { name: "precision-test-1", value: 0.123456 },
            { name: "precision-test-2", value: 0.999999 },
            { name: "precision-test-3", value: 0.0 },
            { name: "precision-test-4", value: 1.0 },
          ]);
        },
        TEST_TIMEOUT
      );

      it(
        "handles special characters in reason field",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `test-special-chars-${timestamp}`,
            input: { test: "special" },
            output: { result: "ok" },
          });

          const specialReason =
            'Contains "quotes", newlines,\nand unicode: 日本語 🎉';

          client.logTracesFeedbackScores([
            {
              id: trace.data.id,
              name: "special-chars-score",
              value: 0.5,
              reason: specialReason,
            },
          ]);
          await client.flush();

          await verifyFeedbackScores(client, "trace", trace.data.id, [
            { name: "special-chars-score", value: 0.5, reason: specialReason },
          ]);
        },
        TEST_TIMEOUT
      );

      it(
        "handles very long reason strings",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `test-long-reason-${timestamp}`,
            input: { test: "long" },
            output: { result: "ok" },
          });

          const longReason = "This is a detailed explanation. ".repeat(50).trim();

          client.logTracesFeedbackScores([
            {
              id: trace.data.id,
              name: "long-reason-score",
              value: 0.75,
              reason: longReason,
            },
          ]);
          await client.flush();

          await verifyFeedbackScores(client, "trace", trace.data.id, [
            { name: "long-reason-score", value: 0.75, reason: longReason },
          ]);
        },
        TEST_TIMEOUT
      );

      it(
        "handles multiple scores logged sequentially",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `test-sequential-scores-${timestamp}`,
            input: { test: "sequential" },
            output: { result: "ok" },
          });

          // Log scores in multiple batches
          client.logTracesFeedbackScores([
            { id: trace.data.id, name: "score-batch-1", value: 0.5 },
          ]);
          await client.flush();

          client.logTracesFeedbackScores([
            { id: trace.data.id, name: "score-batch-2", value: 0.6 },
          ]);
          await client.flush();

          client.logTracesFeedbackScores([
            { id: trace.data.id, name: "score-batch-3", value: 0.7 },
          ]);
          await client.flush();

          // All scores should be present
          await verifyFeedbackScores(client, "trace", trace.data.id, [
            { name: "score-batch-1", value: 0.5 },
            { name: "score-batch-2", value: 0.6 },
            { name: "score-batch-3", value: 0.7 },
          ]);
        },
        EXTENDED_TIMEOUT
      );
    });

    describe("Project Association", () => {
      it(
        "uses per-score projectName when specified",
        async () => {
          const timestamp = Date.now();

          // Create two different projects
          const project1 = `${testProjectName}-proj1-${timestamp}`;
          const project2 = `${testProjectName}-proj2-${timestamp}`;

          const client1 = new Opik({ projectName: project1 });
          const client2 = new Opik({ projectName: project2 });

          try {
            // Create traces in different projects
            const trace1 = client1.trace({
              name: `trace-p1-${timestamp}`,
              input: { project: 1 },
              output: { result: "p1" },
            });
            trace1.end();

            const trace2 = client2.trace({
              name: `trace-p2-${timestamp}`,
              input: { project: 2 },
              output: { result: "p2" },
            });
            trace2.end();

            await client1.flush();
            await client2.flush();

            // Wait for traces
            await client1.searchTraces({
              projectName: project1,
              filterString: `name = "trace-p1-${timestamp}"`,
              waitForAtLeast: 1,
              waitForTimeout: POLL_TIMEOUT_SECONDS,
            });

            await client2.searchTraces({
              projectName: project2,
              filterString: `name = "trace-p2-${timestamp}"`,
              waitForAtLeast: 1,
              waitForTimeout: POLL_TIMEOUT_SECONDS,
            });

            // Log scores with explicit projectName
            client1.logTracesFeedbackScores([
              {
                id: trace1.data.id,
                name: "proj1-score",
                value: 0.9,
                projectName: project1,
              },
            ]);

            client2.logTracesFeedbackScores([
              {
                id: trace2.data.id,
                name: "proj2-score",
                value: 0.8,
                projectName: project2,
              },
            ]);

            await client1.flush();
            await client2.flush();

            // Verify scores are in correct projects
            await verifyFeedbackScores(client1, "trace", trace1.data.id, [
              { name: "proj1-score", value: 0.9 },
            ]);

            await verifyFeedbackScores(client2, "trace", trace2.data.id, [
              { name: "proj2-score", value: 0.8 },
            ]);
          } finally {
            await client1.flush();
            await client2.flush();
          }
        },
        EXTENDED_TIMEOUT
      );

      it(
        "falls back to client default projectName when not specified",
        async () => {
          const timestamp = Date.now();
          const trace = await createTestTrace({
            name: `trace-default-proj-${timestamp}`,
            input: { test: "default" },
            output: { result: "ok" },
          });

          // Log score without explicit projectName - should use client default
          client.logTracesFeedbackScores([
            {
              id: trace.data.id,
              name: "default-project-score",
              value: 0.75,
            },
          ]);
          await client.flush();

          await verifyFeedbackScores(client, "trace", trace.data.id, [
            { name: "default-project-score", value: 0.75 },
          ]);
        },
        TEST_TIMEOUT
      );

      it(
        "handles span feedback scores with proper project association",
        async () => {
          const timestamp = Date.now();
          const { span: span1 } = await createTestSpan({
            traceName: `trace-span-proj-${timestamp}-1`,
            spanName: "span-1",
            input: { step: 1 },
            output: { result: "done" },
          });

          const { span: span2 } = await createTestSpan({
            traceName: `trace-span-proj-${timestamp}-2`,
            spanName: "span-2",
            input: { step: 2 },
            output: { result: "done" },
          });

          // Log feedback scores to both spans
          client.logSpansFeedbackScores([
            {
              id: span1.data.id,
              name: "span-quality",
              value: 0.9,
              reason: "Span 1 performed well",
            },
            {
              id: span2.data.id,
              name: "span-quality",
              value: 0.8,
              reason: "Span 2 was good",
            },
          ]);
          await client.flush();

          // Verify both spans have their scores
          await verifyFeedbackScores(client, "span", span1.data.id, [
            { name: "span-quality", value: 0.9, reason: "Span 1 performed well" },
          ]);

          await verifyFeedbackScores(client, "span", span2.data.id, [
            { name: "span-quality", value: 0.8, reason: "Span 2 was good" },
          ]);
        },
        TEST_TIMEOUT
      );
    });

    describe("Search with Feedback Score Filters", () => {
      it(
        "finds traces with specific feedback score values",
        async () => {
          const timestamp = Date.now();

          // Create traces with different score values
          const traceHigh = await createTestTrace({
            name: `score-filter-high-${timestamp}`,
            input: { quality: "high" },
            output: { result: "excellent" },
          });

          const traceLow = await createTestTrace({
            name: `score-filter-low-${timestamp}`,
            input: { quality: "low" },
            output: { result: "poor" },
          });

          // Add feedback scores
          client.logTracesFeedbackScores([
            { id: traceHigh.data.id, name: "quality_score", value: 0.95 },
            { id: traceLow.data.id, name: "quality_score", value: 0.25 },
          ]);
          await client.flush();

          // Verify scores are persisted
          await verifyFeedbackScores(client, "trace", traceHigh.data.id, [
            { name: "quality_score", value: 0.95 },
          ]);
          await verifyFeedbackScores(client, "trace", traceLow.data.id, [
            { name: "quality_score", value: 0.25 },
          ]);

          // Verify scores can be read back correctly
          const highQualityTrace = await client.api.traces.getTraceById(
            traceHigh.data.id
          );
          const lowQualityTrace = await client.api.traces.getTraceById(
            traceLow.data.id
          );

          const highScore = highQualityTrace.feedbackScores?.find(
            (s) => s.name === "quality_score"
          );
          const lowScore = lowQualityTrace.feedbackScores?.find(
            (s) => s.name === "quality_score"
          );

          expect(highScore?.value).toBeCloseTo(0.95, 4);
          expect(lowScore?.value).toBeCloseTo(0.25, 4);
        },
        EXTENDED_TIMEOUT
      );
    });
  }
);
