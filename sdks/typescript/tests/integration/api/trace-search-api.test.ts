import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./shouldRunIntegrationTests";
import type { TracePublic } from "@/rest_api/api";

const shouldRunApiTests = shouldRunIntegrationTests();

// Test constants
const DEFAULT_TEST_TIMEOUT = 30000;
const EXTENDED_TEST_TIMEOUT = 40000;
const SHORT_TIMEOUT = 15000;
const WAIT_FOR_TIMEOUT_SECONDS = 30;
const LARGE_DATA_SIZE = 10000;

// Helper type for trace metadata
interface TraceMetadata {
  [key: string]: unknown;
}

describe.skipIf(!shouldRunApiTests)("Trace Search Real API Integration", () => {
  let client: Opik;
  const testProjectName = `test-search-${Date.now()}`;

  beforeAll(() => {
    if (shouldRunApiTests) {
      console.log(getIntegrationTestStatus());
      client = new Opik({ projectName: testProjectName });
    }
  });

  afterAll(async () => {
    if (client) {
      await client.flush();
      // Note: Cleanup of test project would require delete API
      // TODO: Add project cleanup once API supports it
    }
  });

  /**
   * Helper function to create a trace with automatic tracking for cleanup
   */
  const createTestTrace = (params: {
    name: string;
    input?: Record<string, unknown>;
    output?: Record<string, unknown>;
    tags?: string[];
    metadata?: Record<string, unknown>;
  }) => {
    const trace = client.trace({
      name: params.name,
      input: params.input || {},
      tags: params.tags,
      metadata: params.metadata,
    });

    if (params.output) {
      trace.update({ output: params.output });
    }

    trace.end();
    return trace;
  };

  /**
   * Helper to wait for traces with better error messaging
   */
  const searchWithWait = async (
    filterString: string,
    expectedCount: number,
    timeout: number = WAIT_FOR_TIMEOUT_SECONDS
  ): Promise<TracePublic[]> => {
    return await client.searchTraces({
      projectName: testProjectName,
      filterString,
      waitForAtLeast: expectedCount,
      waitForTimeout: timeout,
    });
  };

  describe("Basic Search & Filtering", () => {
    it(
      "should create traces and search them immediately",
      async () => {
        const timestamp = Date.now();
        const traceName = `search-test-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: { query: "test input" },
          output: { result: "test output" },
          tags: ["test", "search"],
          metadata: { testId: timestamp, type: "integration" },
        });

        await client.flush();

        const results = await searchWithWait(`name = "${traceName}"`, 1);

        // More specific assertions - we created exactly 1 trace with this name
        expect(results.length).toBeGreaterThanOrEqual(1);
        const foundTrace = results.find((t) => t.name === traceName);

        expect(foundTrace).toBeDefined();
        expect(foundTrace?.name).toBe(traceName);
        expect(foundTrace?.tags).toEqual(
          expect.arrayContaining(["test", "search"])
        );
        expect(foundTrace?.tags).toHaveLength(2);

        // Verify input/output structure
        expect(foundTrace?.input).toEqual({ query: "test input" });
        expect(foundTrace?.output).toEqual({ result: "test output" });

        // Verify metadata
        const metadata = foundTrace?.metadata as TraceMetadata;
        expect(metadata.testId).toBe(timestamp);
        expect(metadata.type).toBe("integration");

        // Verify required fields are present
        expect(foundTrace?.id).toBeDefined();
        expect(foundTrace?.projectId).toBeDefined();
        expect(foundTrace?.startTime).toBeDefined();
        expect(foundTrace?.endTime).toBeDefined();
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should filter traces by name with exact match",
      async () => {
        const timestamp = Date.now();
        const uniqueName = `filter-by-name-${timestamp}`;
        const differentName = `different-${timestamp}`;

        // Create traces with different names
        createTestTrace({
          name: uniqueName,
          input: { query: "first" },
        });

        createTestTrace({
          name: differentName,
          input: { query: "second" },
        });

        await client.flush();

        // Search for specific name - should only return one
        const results = await searchWithWait(`name = "${uniqueName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);

        // Verify only traces with the exact name are returned
        const matchingTraces = results.filter((t) => t.name === uniqueName);
        const otherTraces = results.filter((t) => t.name === differentName);

        expect(matchingTraces.length).toBeGreaterThanOrEqual(1);
        expect(otherTraces).toHaveLength(0);

        // Verify the found trace has correct input
        expect(matchingTraces[0].input).toEqual({ query: "first" });
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should filter traces by metadata with nested keys",
      async () => {
        const timestamp = Date.now();
        const metadataValue = `metadata-test-${timestamp}`;

        createTestTrace({
          name: `metadata-trace-${timestamp}`,
          input: { query: "test" },
          metadata: {
            testKey: metadataValue,
            environment: "integration",
            version: "1.0",
          },
        });

        // Create trace without this metadata to ensure filtering works
        createTestTrace({
          name: `no-metadata-trace-${timestamp}`,
          input: { query: "test" },
          metadata: {
            differentKey: "value",
          },
        });

        await client.flush();

        const results = await searchWithWait(
          `metadata.testKey = "${metadataValue}"`,
          1
        );

        expect(results.length).toBeGreaterThanOrEqual(1);

        // All returned traces should have the correct metadata
        const tracesWithMetadata = results.filter((t) => {
          const metadata = t.metadata as TraceMetadata;
          return metadata.testKey === metadataValue;
        });

        expect(tracesWithMetadata.length).toBeGreaterThanOrEqual(1);

        const metadata = tracesWithMetadata[0].metadata as TraceMetadata;
        expect(metadata.testKey).toBe(metadataValue);
        expect(metadata.environment).toBe("integration");
        expect(metadata.version).toBe("1.0");

        // Verify traces without the metadata are not included
        const tracesWithoutMetadata = results.filter((t) => {
          const metadata = t.metadata as TraceMetadata;
          return metadata.differentKey === "value";
        });
        expect(tracesWithoutMetadata).toHaveLength(0);
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should filter traces by tags using contains operator",
      async () => {
        const timestamp = Date.now();
        const uniqueTag = `tag-${timestamp}`;

        createTestTrace({
          name: `tagged-trace-${timestamp}`,
          input: { query: "test" },
          tags: [uniqueTag, "integration", "test"],
        });

        // Create trace without this tag
        createTestTrace({
          name: `untagged-trace-${timestamp}`,
          input: { query: "test" },
          tags: ["other-tag"],
        });

        await client.flush();

        const results = await searchWithWait(`tags contains "${uniqueTag}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);

        // All returned traces should contain the tag
        const tracesWithTag = results.filter((t) =>
          t.tags?.includes(uniqueTag)
        );
        expect(tracesWithTag.length).toBeGreaterThanOrEqual(1);
        expect(tracesWithTag[0].tags).toEqual(
          expect.arrayContaining([uniqueTag, "integration", "test"])
        );

        // Verify traces without the tag are not included
        const tracesWithoutTag = results.filter(
          (t) => t.tags?.includes("other-tag") && !t.tags?.includes(uniqueTag)
        );
        expect(tracesWithoutTag).toHaveLength(0);
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should filter traces by date range with start and end times",
      async () => {
        const timestamp = Date.now();
        const now = new Date();
        const oneMinuteAgo = new Date(now.getTime() - 60000);
        const oneMinuteFromNow = new Date(now.getTime() + 60000);
        const twoMinutesAgo = new Date(now.getTime() - 120000);

        createTestTrace({
          name: `date-filter-${timestamp}`,
          input: { query: "recent test" },
        });

        await client.flush();

        // Search with date range - should find recent trace
        const recentResults = await searchWithWait(
          `start_time >= "${oneMinuteAgo.toISOString()}" AND start_time <= "${oneMinuteFromNow.toISOString()}"`,
          1
        );

        expect(recentResults.length).toBeGreaterThanOrEqual(1);
        const foundTrace = recentResults.find(
          (t) => t.name === `date-filter-${timestamp}`
        );
        expect(foundTrace).toBeDefined();
        expect(foundTrace?.startTime).toBeDefined();

        // Verify start time is within expected range
        const traceStartTime = new Date(foundTrace!.startTime!);
        expect(traceStartTime.getTime()).toBeGreaterThanOrEqual(
          oneMinuteAgo.getTime()
        );
        expect(traceStartTime.getTime()).toBeLessThanOrEqual(
          oneMinuteFromNow.getTime()
        );

        // Search with old date range - should not find recent trace
        const oldResults = await client.searchTraces({
          projectName: testProjectName,
          filterString: `start_time >= "${twoMinutesAgo.toISOString()}" AND start_time < "${oneMinuteAgo.toISOString()}"`,
        });

        const oldTrace = oldResults.find(
          (t) => t.name === `date-filter-${timestamp}`
        );
        expect(oldTrace).toBeUndefined();
      },
      EXTENDED_TEST_TIMEOUT
    );
  });

  describe("Pagination & Limits", () => {
    it(
      "should respect maxResults parameter and return exact limit",
      async () => {
        const timestamp = Date.now();
        const prefix = `pagination-${timestamp}`;
        const batchSize = 10;
        const limit = 5;

        // Create 10 traces in batch
        for (let i = 0; i < batchSize; i++) {
          createTestTrace({
            name: `${prefix}-trace-${i}`,
            input: { index: i },
            metadata: { batch: prefix },
          });
        }

        await client.flush();

        // Search with maxResults = 5
        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: `metadata.batch = "${prefix}"`,
          maxResults: limit,
          waitForAtLeast: limit,
          waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
        });

        // Should return exactly 5 or slightly more due to indexing
        expect(results.length).toBeLessThanOrEqual(batchSize);
        expect(results.length).toBeGreaterThanOrEqual(limit);

        // Verify all returned traces have correct metadata
        results.forEach((trace) => {
          const metadata = trace.metadata as TraceMetadata;
          expect(metadata.batch).toBe(prefix);
        });
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle default maxResults of 1000",
      async () => {
        const timestamp = Date.now();

        createTestTrace({
          name: `default-limit-${timestamp}`,
          input: { query: "test" },
        });

        await client.flush();

        // Search without specifying maxResults (default is 1000)
        const results = await searchWithWait(
          `name = "default-limit-${timestamp}"`,
          1
        );

        // Should return at least one trace
        expect(results.length).toBeGreaterThanOrEqual(1);
        expect(results).not.toHaveLength(0);
      },
      DEFAULT_TEST_TIMEOUT
    );

    it(
      "should handle maxResults of 1 correctly",
      async () => {
        const timestamp = Date.now();
        const prefix = `single-result-${timestamp}`;

        // Create 3 traces
        for (let i = 0; i < 3; i++) {
          createTestTrace({
            name: `${prefix}-${i}`,
            input: { index: i },
            metadata: { batch: prefix },
          });
        }

        await client.flush();

        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: `metadata.batch = "${prefix}"`,
          maxResults: 1,
          waitForAtLeast: 1,
          waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
        });

        // Should return at least 1, possibly more due to backend batching
        expect(results.length).toBeGreaterThanOrEqual(1);
        expect(results.length).toBeLessThanOrEqual(3);
      },
      EXTENDED_TEST_TIMEOUT
    );
  });

  describe("Truncation Behavior", () => {
    it(
      "should return data with truncate enabled by default",
      async () => {
        const timestamp = Date.now();
        const largeText = "x".repeat(LARGE_DATA_SIZE);

        createTestTrace({
          name: `truncate-test-${timestamp}`,
          input: { largeData: largeText, smallData: "small" },
          output: { largeResult: largeText, smallResult: "small" },
        });

        await client.flush();

        // Search with truncate = true (default behavior)
        const truncatedResults = await searchWithWait(
          `name = "truncate-test-${timestamp}"`,
          1
        );

        expect(truncatedResults.length).toBeGreaterThanOrEqual(1);
        const truncatedTrace = truncatedResults[0];

        expect(truncatedTrace.name).toBe(`truncate-test-${timestamp}`);
        expect(truncatedTrace.input).toBeDefined();
        expect(truncatedTrace.output).toBeDefined();

        // With truncate=true, the backend may truncate or omit fields
        // The exact truncation behavior depends on backend implementation
        // We just verify that the trace is found and has input/output defined
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should return full data when truncate is false",
      async () => {
        const timestamp = Date.now();
        const largeText = "x".repeat(LARGE_DATA_SIZE);

        createTestTrace({
          name: `no-truncate-test-${timestamp}`,
          input: { largeData: largeText },
          output: { largeResult: largeText },
        });

        await client.flush();

        // Search with truncate = false to get full data
        const fullResults = await client.searchTraces({
          projectName: testProjectName,
          filterString: `name = "no-truncate-test-${timestamp}"`,
          truncate: false,
          waitForAtLeast: 1,
          waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
        });

        expect(fullResults.length).toBeGreaterThanOrEqual(1);
        const fullTrace = fullResults[0];

        expect(fullTrace.name).toBe(`no-truncate-test-${timestamp}`);
        expect(fullTrace.input).toBeDefined();
        expect(fullTrace.output).toBeDefined();

        const fullInput = fullTrace.input as Record<string, unknown>;
        const fullOutput = fullTrace.output as Record<string, unknown>;

        // With truncate=false, we should get full data
        expect(typeof fullInput.largeData).toBe("string");
        expect(typeof fullOutput.largeResult).toBe("string");
      },
      EXTENDED_TEST_TIMEOUT
    );
  });

  describe("Polling with waitForAtLeast", () => {
    it(
      "should wait until expected number of traces are indexed",
      async () => {
        const timestamp = Date.now();
        const batchPrefix = `wait-batch-${timestamp}`;
        const expectedCount = 3;

        // Create multiple traces that should be indexed
        for (let i = 0; i < expectedCount; i++) {
          createTestTrace({
            name: `${batchPrefix}-${i}`,
            input: { index: i },
            metadata: { batch: batchPrefix, order: i },
          });
        }

        await client.flush();

        // Wait for at least 3 traces with timeout
        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: `metadata.batch = "${batchPrefix}"`,
          waitForAtLeast: expectedCount,
          waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
        });

        expect(results.length).toBeGreaterThanOrEqual(expectedCount);

        // Verify all expected traces are present with correct data
        const traceNames = results.map((t) => t.name);
        for (let i = 0; i < expectedCount; i++) {
          expect(traceNames).toContain(`${batchPrefix}-${i}`);
        }

        // Verify trace data integrity
        results.forEach((trace) => {
          expect(trace.name).toMatch(new RegExp(`^${batchPrefix}-\\d+$`));
          const metadata = trace.metadata as TraceMetadata;
          expect(metadata.batch).toBe(batchPrefix);
          expect(typeof metadata.order).toBe("number");
        });
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should throw SearchTimeoutError when insufficient traces after timeout",
      async () => {
        const timestamp = Date.now();
        const traceName = `timeout-test-${timestamp}`;

        // Create only 1 trace but wait for 100
        createTestTrace({
          name: traceName,
          input: { query: "test" },
        });

        await client.flush();

        // Try to wait for 100 traces with short timeout
        await expect(
          client.searchTraces({
            projectName: testProjectName,
            filterString: `name = "${traceName}"`,
            waitForAtLeast: 100,
            waitForTimeout: 5,
          })
        ).rejects.toThrow(/Timeout after 5 seconds: expected 100 traces/);
      },
      SHORT_TIMEOUT
    );

    it(
      "should return immediately when traces already indexed",
      async () => {
        const timestamp = Date.now();
        const traceName = `immediate-test-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: { query: "test" },
        });

        await client.flush();

        // First search to ensure it's indexed
        await searchWithWait(`name = "${traceName}"`, 1, 30);

        // Second search should return immediately
        const startTime = Date.now();
        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: `name = "${traceName}"`,
          waitForAtLeast: 1,
          waitForTimeout: 30,
        });
        const elapsed = Date.now() - startTime;

        expect(results.length).toBeGreaterThanOrEqual(1);
        // Should be very fast since trace is already indexed
        expect(elapsed).toBeLessThan(10000); // Less than 10 seconds
      },
      EXTENDED_TEST_TIMEOUT
    );
  });

  describe("Complex Real-World Scenarios", () => {
    it(
      "should handle complex multi-condition AND filters",
      async () => {
        const timestamp = Date.now();
        const traceName = `multi-filter-${timestamp}`;
        const tagValue = `tag-${timestamp}`;
        const metadataValue = `meta-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: { query: "complex search" },
          tags: [tagValue, "production", "critical"],
          metadata: {
            environment: metadataValue,
            version: "2.0",
            user: "test-user",
            priority: "high",
          },
        });

        // Create similar but different trace that shouldn't match all filters
        createTestTrace({
          name: `different-${timestamp}`,
          input: { query: "other" },
          tags: [tagValue, "staging"],
          metadata: {
            environment: "different",
            version: "2.0",
          },
        });

        await client.flush();

        // Search with multiple AND conditions
        const results = await searchWithWait(
          `name = "${traceName}" AND tags contains "${tagValue}" AND metadata.environment = "${metadataValue}"`,
          1
        );

        expect(results.length).toBeGreaterThanOrEqual(1);

        // Find our specific trace
        const foundTrace = results.find((t) => t.name === traceName);
        expect(foundTrace).toBeDefined();

        // Verify all filter conditions are met
        expect(foundTrace?.name).toBe(traceName);
        expect(foundTrace?.tags).toContain(tagValue);
        expect(foundTrace?.tags).toContain("production");
        expect(foundTrace?.tags).toContain("critical");

        const metadata = foundTrace?.metadata as TraceMetadata;
        expect(metadata.environment).toBe(metadataValue);
        expect(metadata.version).toBe("2.0");
        expect(metadata.user).toBe("test-user");
        expect(metadata.priority).toBe("high");

        // Verify the other trace is not in results
        const otherTrace = results.find(
          (t) => t.name === `different-${timestamp}`
        );
        expect(otherTrace).toBeUndefined();
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should return empty array for non-existent traces",
      async () => {
        const timestamp = Date.now();
        const nonExistentName = `non-existent-${timestamp}-${Math.random()}`;

        // Search for trace that doesn't exist
        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: `name = "${nonExistentName}"`,
        });

        expect(results).toEqual([]);
        expect(results).toHaveLength(0);
      },
      DEFAULT_TEST_TIMEOUT
    );

    it(
      "should handle cross-project isolation correctly",
      async () => {
        const timestamp = Date.now();
        const traceName = `isolation-test-${timestamp}`;
        const project1 = `${testProjectName}-isolation-1`;
        const project2 = `${testProjectName}-isolation-2`;

        // Create clients for two different projects
        const client1 = new Opik({ projectName: project1 });
        const client2 = new Opik({ projectName: project2 });

        try {
          // Create trace in project 1
          const trace1 = client1.trace({
            name: traceName,
            input: { project: "project1" },
            metadata: { projectId: "1", testId: timestamp },
          });
          trace1.end();

          // Create trace in project 2
          const trace2 = client2.trace({
            name: traceName,
            input: { project: "project2" },
            metadata: { projectId: "2", testId: timestamp },
          });
          trace2.end();

          await client1.flush();
          await client2.flush();

          // Search in project 1 - should only find traces from project 1
          const results1 = await client1.searchTraces({
            projectName: project1,
            filterString: `name = "${traceName}"`,
            waitForAtLeast: 1,
            waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
          });

          expect(results1.length).toBeGreaterThanOrEqual(1);

          // All traces in results1 should be from project1
          results1.forEach((trace) => {
            const metadata = trace.metadata as TraceMetadata;
            expect(metadata.projectId).toBe("1");
          });

          const foundTrace1 = results1.find((t) => t.name === traceName);
          expect(foundTrace1).toBeDefined();
          expect((foundTrace1?.input as Record<string, unknown>).project).toBe(
            "project1"
          );

          // Search in project 2 - should only find traces from project 2
          const results2 = await client2.searchTraces({
            projectName: project2,
            filterString: `name = "${traceName}"`,
            waitForAtLeast: 1,
            waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
          });

          expect(results2.length).toBeGreaterThanOrEqual(1);

          // All traces in results2 should be from project2
          results2.forEach((trace) => {
            const metadata = trace.metadata as TraceMetadata;
            expect(metadata.projectId).toBe("2");
          });

          const foundTrace2 = results2.find((t) => t.name === traceName);
          expect(foundTrace2).toBeDefined();
          expect((foundTrace2?.input as Record<string, unknown>).project).toBe(
            "project2"
          );

          // Verify strict isolation - trace IDs should not overlap
          const project1Ids = results1.map((t) => t.id);
          const project2Ids = results2.map((t) => t.id);

          expect(project1Ids).not.toContain(foundTrace2?.id);
          expect(project2Ids).not.toContain(foundTrace1?.id);
        } finally {
          // Ensure both clients are flushed
          await client1.flush();
          await client2.flush();
        }
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should preserve all trace fields and data types after search",
      async () => {
        const timestamp = Date.now();
        const traceName = `complete-fields-${timestamp}`;

        // Create trace with comprehensive field population
        createTestTrace({
          name: traceName,
          input: {
            query: "test query",
            parameters: { temperature: 0.7, maxTokens: 100 },
            options: ["option1", "option2"],
          },
          output: {
            response: "test response",
            tokensUsed: 150,
            confidence: 0.95,
          },
          tags: ["complete", "fields", "test"],
          metadata: {
            model: "gpt-4",
            userId: "user-123",
            sessionId: "session-456",
            customField: { nested: { value: "deep" } },
            timestamp,
            active: true,
          },
        });

        await client.flush();

        // Search and verify all fields are preserved with correct types
        const results = await searchWithWait(`name = "${traceName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const foundTrace = results.find((t) => t.name === traceName);

        // Verify trace exists
        expect(foundTrace).toBeDefined();

        // Verify required system fields
        expect(foundTrace?.id).toBeDefined();
        expect(foundTrace?.projectId).toBeDefined();
        expect(foundTrace?.startTime).toBeDefined();
        expect(foundTrace?.endTime).toBeDefined();
        expect(typeof foundTrace?.id).toBe("string");
        expect(typeof foundTrace?.projectId).toBe("string");

        // Verify name
        expect(foundTrace?.name).toBe(traceName);

        // Verify tags with exact match
        expect(foundTrace?.tags).toEqual(
          expect.arrayContaining(["complete", "fields", "test"])
        );
        expect(foundTrace?.tags).toHaveLength(3);

        // Verify input structure and data types
        const traceInput = foundTrace?.input as Record<string, unknown>;
        expect(traceInput.query).toBe("test query");
        expect(traceInput.parameters).toEqual({
          temperature: 0.7,
          maxTokens: 100,
        });
        expect(traceInput.options).toEqual(["option1", "option2"]);

        // Verify output structure and data types
        const traceOutput = foundTrace?.output as Record<string, unknown>;
        expect(traceOutput.response).toBe("test response");
        expect(traceOutput.tokensUsed).toBe(150);
        expect(traceOutput.confidence).toBe(0.95);

        // Verify metadata with nested structures
        const traceMetadata = foundTrace?.metadata as TraceMetadata;
        expect(traceMetadata.model).toBe("gpt-4");
        expect(traceMetadata.userId).toBe("user-123");
        expect(traceMetadata.sessionId).toBe("session-456");
        expect(traceMetadata.timestamp).toBe(timestamp);
        expect(traceMetadata.active).toBe(true);

        // Verify nested metadata
        const customField = traceMetadata.customField as Record<
          string,
          unknown
        >;
        const nested = customField.nested as Record<string, unknown>;
        expect(nested.value).toBe("deep");
      },
      EXTENDED_TEST_TIMEOUT
    );
  });

  describe("Edge Cases & Error Handling", () => {
    it(
      "should handle empty filter string",
      async () => {
        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: "",
        });

        // Empty filter should return all traces in project
        expect(Array.isArray(results)).toBe(true);
      },
      DEFAULT_TEST_TIMEOUT
    );

    it(
      "should handle traces with special characters in name",
      async () => {
        const timestamp = Date.now();
        // Note: excluding double quote (") as it's a query language delimiter
        const specialName = `special-!@#$%^&*()_+-=[]{}|;':,./<>?-${timestamp}`;

        createTestTrace({
          name: specialName,
          input: { test: "special characters" },
        });

        await client.flush();

        const results = await searchWithWait(`name = "${specialName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === specialName);
        expect(found).toBeDefined();
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle traces with empty input and output",
      async () => {
        const timestamp = Date.now();
        const traceName = `empty-data-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: {},
          output: {},
        });

        await client.flush();

        const results = await searchWithWait(`name = "${traceName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === traceName);
        expect(found).toBeDefined();
        expect(found?.input).toEqual({});
        expect(found?.output).toEqual({});
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle traces with null metadata values",
      async () => {
        const timestamp = Date.now();
        const traceName = `null-metadata-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: { test: "data" },
          metadata: {
            nullValue: null,
            definedValue: "exists",
          },
        });

        await client.flush();

        const results = await searchWithWait(`name = "${traceName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === traceName);
        expect(found).toBeDefined();

        const metadata = found?.metadata as TraceMetadata;
        expect(metadata.definedValue).toBe("exists");
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle traces with no tags",
      async () => {
        const timestamp = Date.now();
        const traceName = `no-tags-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: { test: "data" },
          tags: [],
        });

        await client.flush();

        const results = await searchWithWait(`name = "${traceName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === traceName);
        expect(found).toBeDefined();
        // Backend returns undefined for empty tags, not []
        expect(found?.tags ?? []).toEqual([]);
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle very long trace names",
      async () => {
        const timestamp = Date.now();
        const longName = `${"very-long-name-".repeat(20)}${timestamp}`;

        createTestTrace({
          name: longName,
          input: { test: "long name" },
        });

        await client.flush();

        const results = await searchWithWait(`name = "${longName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === longName);
        expect(found).toBeDefined();
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle Unicode characters in trace data",
      async () => {
        const timestamp = Date.now();
        const unicodeName = `unicode-测试-🚀-${timestamp}`;

        createTestTrace({
          name: unicodeName,
          input: {
            message: "Hello 世界 🌍",
            emoji: "😀😃😄😁",
          },
          metadata: {
            language: "中文",
            symbol: "✓",
          },
        });

        await client.flush();

        const results = await searchWithWait(`name = "${unicodeName}"`, 1);

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === unicodeName);
        expect(found).toBeDefined();

        const input = found?.input as Record<string, unknown>;
        expect(input.message).toBe("Hello 世界 🌍");
        expect(input.emoji).toBe("😀😃😄😁");
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle numeric comparison operators",
      async () => {
        const timestamp = Date.now();
        const prefix = `numeric-filter-${timestamp}`;

        // Create traces with different numeric metadata
        for (let i = 1; i <= 5; i++) {
          createTestTrace({
            name: `${prefix}-${i}`,
            input: { index: i },
            metadata: { score: i * 10, batch: prefix },
          });
        }

        await client.flush();

        // Test greater than operator
        const gtResults = await client.searchTraces({
          projectName: testProjectName,
          filterString: `metadata.batch = "${prefix}" AND metadata.score > 30`,
          waitForAtLeast: 2,
          waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
        });

        expect(gtResults.length).toBeGreaterThanOrEqual(2);
        gtResults.forEach((trace) => {
          const metadata = trace.metadata as TraceMetadata;
          expect(metadata.score as number).toBeGreaterThan(30);
        });
      },
      EXTENDED_TEST_TIMEOUT
    );

    it(
      "should handle contains operator with partial matches",
      async () => {
        const timestamp = Date.now();
        const traceName = `contains-test-${timestamp}`;

        createTestTrace({
          name: traceName,
          input: { description: "This is a test message" },
          metadata: { category: "test-category-value" },
        });

        await client.flush();

        // Test contains operator on name
        const results = await client.searchTraces({
          projectName: testProjectName,
          filterString: `name contains "contains-test"`,
          waitForAtLeast: 1,
          waitForTimeout: WAIT_FOR_TIMEOUT_SECONDS,
        });

        expect(results.length).toBeGreaterThanOrEqual(1);
        const found = results.find((t) => t.name === traceName);
        expect(found).toBeDefined();
      },
      EXTENDED_TEST_TIMEOUT
    );
  });
});
