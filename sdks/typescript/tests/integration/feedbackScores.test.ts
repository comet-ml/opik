/**
 * Integration tests for logTracesFeedbackScores and logSpansFeedbackScores.
 *
 * These tests verify that feedback scores can be logged to traces and spans
 * via the batch queue system, AND that they are actually persisted to the backend.
 *
 * Edge case testing (decimal values, special characters, long strings, etc.)
 * is covered in unit tests: tests/unit/client/client-feedbackScores.test.ts
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
      client = new Opik({ projectName: testProjectName });
    });

    afterAll(async () => {
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
  }
);
