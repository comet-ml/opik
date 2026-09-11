import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";
import type { TraceThread } from "@/rest_api/api";

const shouldRunApiTests = shouldRunIntegrationTests();

const EXTENDED_TEST_TIMEOUT = 40000;
const WAIT_FOR_TIMEOUT_SECONDS = 30;

describe.skipIf(!shouldRunApiTests)("Thread Search Real API Integration", () => {
  let client: Opik;
  const testProjectName = `test-thread-search-${Date.now()}`;

  beforeAll(() => {
    if (shouldRunApiTests) {
      console.log(getIntegrationTestStatus());
      client = new Opik({ projectName: testProjectName });
    }
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
    }
  });

  const createTestThread = (params: {
    threadId: string;
    traceName: string;
    input?: Record<string, unknown>;
    output?: Record<string, unknown>;
    metadata?: Record<string, unknown>;
  }) => {
    const trace = client.trace({
      name: params.traceName,
      input: params.input || {},
      metadata: params.metadata,
      threadId: params.threadId,
    });

    if (params.output) {
      trace.update({ output: params.output });
    }

    trace.end();
    return trace;
  };

  const searchThreadsWithWait = async (
    filterString: string,
    expectedCount: number,
    timeout: number = WAIT_FOR_TIMEOUT_SECONDS
  ): Promise<TraceThread[]> => {
    return await client.searchThreads({
      projectName: testProjectName,
      filterString,
      waitForAtLeast: expectedCount,
      waitForTimeout: timeout,
    });
  };

  it(
    "should filter threads using multiple conditions in filter string",
    async () => {
      const timestamp = Date.now();
      const uniqueMarker = `marker-${timestamp}`;

      createTestThread({
        threadId: `thread-1-${timestamp}`,
        traceName: `trace-${timestamp}-1`,
        input: { query: `${uniqueMarker} first query` },
        output: { response: "first response" },
        metadata: {
          environment: "prod",
          priority: "high",
        },
      });

      createTestThread({
        threadId: `thread-2-${timestamp}`,
        traceName: `trace-${timestamp}-2`,
        input: { query: `${uniqueMarker} second query` },
        output: { response: "second response" },
        metadata: {
          environment: "prod",
          priority: "low",
        },
      });

      createTestThread({
        threadId: `thread-3-${timestamp}`,
        traceName: `trace-${timestamp}-3`,
        input: { query: "different query without marker" },
        output: { response: "third response" },
        metadata: {
          environment: "dev",
          priority: "medium",
        },
      });

      await client.flush();

      const results = await searchThreadsWithWait(
        `first_message contains "${uniqueMarker}" AND number_of_messages > 0`,
        2
      );

      expect(results.length).toBeGreaterThanOrEqual(2);

      results.forEach((thread) => {
        expect(thread.id).toBeDefined();
        expect(thread.projectId).toBeDefined();
        expect(thread.startTime).toBeDefined();
        expect(thread.numberOfMessages).toBeGreaterThan(0);
        expect(JSON.stringify(thread.firstMessage)).toContain(uniqueMarker);
      });

      const threadsWithoutMarker = results.filter((t) =>
        !JSON.stringify(t.firstMessage).includes(uniqueMarker)
      );
      expect(threadsWithoutMarker).toHaveLength(0);
    },
    EXTENDED_TEST_TIMEOUT
  );

  it(
    "should search threads with maxResults and truncate parameters",
    async () => {
      const timestamp = Date.now();
      const prefix = `thread-params-${timestamp}`;
      const batchSize = 5;

      for (let i = 0; i < batchSize; i++) {
        createTestThread({
          threadId: `${prefix}-${i}`,
          traceName: `trace-${prefix}-${i}`,
          input: { index: i },
          metadata: { batch: prefix },
        });
      }

      await client.flush();

      const results = await client.searchThreads({
        projectName: testProjectName,
        maxResults: 3,
        truncate: false,
        waitForAtLeast: 3,
        waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
      });

      expect(results.length).toBeGreaterThanOrEqual(3);

      results.forEach((thread) => {
        expect(thread.id).toBeDefined();
        expect(thread.projectId).toBeDefined();
        expect(thread.startTime).toBeDefined();
        expect(thread.numberOfMessages).toBeDefined();
      });
    },
    EXTENDED_TEST_TIMEOUT
  );
});
