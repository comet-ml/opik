/**
 * Integration test for AnnotationQueue operations in the TypeScript SDK.
 * This is a sanity check for the happy path of trace and thread queue operations.
 * More extensive edge cases and error handling are covered in unit tests.
 */
import { describe, it, expect, beforeAll, afterAll } from "vitest";
import { Opik } from "@/index";
import { TracesAnnotationQueue, ThreadsAnnotationQueue } from "@/annotation-queue";
import { searchAndWaitForDone } from "@/utils/searchHelpers";
import {
  shouldRunIntegrationTests,
  getIntegrationTestStatus,
} from "./api/shouldRunIntegrationTests";
import { logger } from "@/utils/logger";
import { MockInstance } from "vitest";

const shouldRunApiTests = shouldRunIntegrationTests();

describe.skipIf(!shouldRunApiTests)(
  "AnnotationQueue Integration Tests",
  () => {
    let client: Opik;
    let loggerErrorSpy: MockInstance<typeof logger.error>;
    const createdQueueIds: string[] = [];
    const createdProjectNames: string[] = [];

    beforeAll(() => {
      console.log(getIntegrationTestStatus());

      if (!shouldRunApiTests) {
        return;
      }

      client = new Opik();
      loggerErrorSpy = vi.spyOn(logger, "error");
    });

    afterAll(async () => {
      if (!client) {
        return;
      }

      expect(loggerErrorSpy).not.toHaveBeenCalled();
      loggerErrorSpy.mockRestore();

      for (const queueId of createdQueueIds) {
        try {
          await client.deleteTracesAnnotationQueue(queueId);
        } catch (error) {
          console.warn(`Failed to cleanup traces queue "${queueId}":`, error);
        }
        try {
            await client.deleteThreadsAnnotationQueue(queueId);
        } catch (error) {
            console.warn(`Failed to cleanup threads queue "${queueId}":`, error);
        }
      }
    });

    it("should perform complete trace queue flow: create, add traces, remove traces, delete", async () => {
      const testQueueName = `test-trace-queue-${Date.now()}`;
      const testProjectName = `test-project-${Date.now()}`;
      createdProjectNames.push(testProjectName);

      const queue = await client.createTracesAnnotationQueue({
        name: testQueueName,
        description: "Test queue for trace operations",
        instructions: "Review traces for accuracy",
      });
      createdQueueIds.push(queue.id);

      expect(queue).toBeInstanceOf(TracesAnnotationQueue);
      expect(queue.name).toBe(testQueueName);
      expect(queue.scope).toBe("trace");
      expect(queue.description).toBe("Test queue for trace operations");

      // CREATE TRACES: Create some traces to add to the queue
      const trace1 = client.trace({
        name: "test-trace-1",
        projectName: testProjectName,
        input: { message: "Hello" },
        output: { response: "Hi there" },
      });
      trace1.end();

      const trace2 = client.trace({
        name: "test-trace-2",
        projectName: testProjectName,
        input: { message: "Goodbye" },
        output: { response: "See you" },
      });
      trace2.end();

      await client.flush();

      // Wait for traces to be available
      const traces = await searchAndWaitForDone(
        async () => {
          return await client.searchTraces({
            projectName: testProjectName,
          });
        },
        2,
        30000,
        2000
      );

      expect(traces.length).toBeGreaterThanOrEqual(2);

      // ADD TRACES: Add traces to the queue
      await queue.addTraces(traces);

      // Verify items count increased
      const updatedCount = await queue.getItemsCount();
      expect(updatedCount).toBeGreaterThanOrEqual(2);

      // REMOVE TRACES: Remove one trace from the queue
      await queue.removeTraces([traces[0]]);

      // Verify items count decreased
      const countAfterRemove = await queue.getItemsCount();
      expect(countAfterRemove).toBeLessThan(updatedCount!);

      // UPDATE: Update queue properties
      await queue.update({
        description: "Updated description",
        instructions: "Updated instructions",
      });

      const fetchedQueue = await client.getTracesAnnotationQueue(queue.id);
      expect(fetchedQueue.description).toBe("Updated description");
      expect(fetchedQueue.instructions).toBe("Updated instructions");

      await queue.delete();

      const index = createdQueueIds.indexOf(queue.id);
      if (index > -1) {
        createdQueueIds.splice(index, 1);
      }

      await expect(client.getTracesAnnotationQueue(queue.id)).rejects.toThrow();
    });

    it("should perform complete thread queue flow: create, add threads, remove threads, delete", async () => {
      const testQueueName = `test-thread-queue-${Date.now()}`;
      const testProjectName = `test-project-threads-${Date.now()}`;
      const threadId = `thread-${Date.now()}`;
      createdProjectNames.push(testProjectName);

      const queue = await client.createThreadsAnnotationQueue({
        name: testQueueName,
        description: "Test queue for thread operations",
      });
      createdQueueIds.push(queue.id);

      expect(queue).toBeInstanceOf(ThreadsAnnotationQueue);
      expect(queue.name).toBe(testQueueName);
      expect(queue.scope).toBe("thread");

      // CREATE TRACES WITH THREAD_ID: Create traces that form a thread
      const trace1 = client.trace({
        name: "thread-trace-1",
        projectName: testProjectName,
        threadId: threadId,
        input: { message: "First message" },
        output: { response: "First response" },
      });
      trace1.end();

      const trace2 = client.trace({
        name: "thread-trace-2",
        projectName: testProjectName,
        threadId: threadId,
        input: { message: "Second message" },
        output: { response: "Second response" },
      });
      trace2.end();

      await client.flush();

      // Wait for traces to be available
      await searchAndWaitForDone(
        async () => {
          return await client.searchTraces({
            projectName: testProjectName,
          });
        },
        2,
        30000,
        2000
      );

      // GET THREADS: Search for threads using searchThreads
      const threads = await searchAndWaitForDone(
        async () => {
          return await client.searchThreads({
            projectName: testProjectName,
          });
        },
        1,
        30000,
        2000
      );

      expect(threads.length).toBeGreaterThanOrEqual(1);

      // Find our thread
      const ourThread = threads.find((t) => t.id === threadId);
      expect(ourThread).toBeDefined();

      // ADD THREADS: Add thread to the queue
      await queue.addThreads([ourThread!]);

      // Verify items count increased
      const updatedCount = await queue.getItemsCount();
      expect(updatedCount).toBeGreaterThanOrEqual(1);

      // REMOVE THREADS: Remove the thread from the queue
      await queue.removeThreads([ourThread!]);

      // Verify items count decreased
      const countAfterRemove = await queue.getItemsCount();
      expect(countAfterRemove).toBe(0);

      await queue.delete();

      const index2 = createdQueueIds.indexOf(queue.id);
      if (index2 > -1) {
        createdQueueIds.splice(index2, 1);
      }

      await expect(client.getThreadsAnnotationQueue(queue.id)).rejects.toThrow();
    });
  }
);
