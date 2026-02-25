import { Opik } from "opik";
import { MockInstance } from "vitest";
import { ThreadsAnnotationQueue } from "@/annotation-queue";
import {
  AnnotationQueueItemMissingIdError,
} from "@/annotation-queue/errors";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";
import * as OpikApi from "@/rest_api/api";

describe("ThreadsAnnotationQueue", () => {
  let client: Opik;
  let loggerDebugSpy: MockInstance<typeof logger.debug>;
  let loggerErrorSpy: MockInstance<typeof logger.error>;

  beforeEach(() => {
    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    loggerDebugSpy = vi.spyOn(logger, "debug");
    loggerErrorSpy = vi.spyOn(logger, "error");
  });

  afterEach(() => {
    expect(loggerErrorSpy).not.toHaveBeenCalled();
    loggerDebugSpy.mockRestore();
    loggerErrorSpy.mockRestore();
  });

  describe("ThreadsAnnotationQueue class", () => {
    describe("constructor and properties", () => {
      it("should create instance with all properties correctly assigned", () => {
        const data: OpikApi.AnnotationQueuePublic = {
          id: "queue-id",
          name: "Test Threads Queue",
          projectId: "project-id",
          scope: "thread",
          description: "Test description",
          instructions: "Test instructions",
          commentsEnabled: true,
          feedbackDefinitionNames: ["metric1", "metric2"],
        };

        const queue = new ThreadsAnnotationQueue(data, client);

        expect(queue.id).toBe("queue-id");
        expect(queue.name).toBe("Test Threads Queue");
        expect(queue.projectId).toBe("project-id");
        expect(queue.scope).toBe("thread");
        expect(queue.description).toBe("Test description");
        expect(queue.instructions).toBe("Test instructions");
        expect(queue.commentsEnabled).toBe(true);
        expect(queue.feedbackDefinitionNames).toEqual(["metric1", "metric2"]);
      });
    });

    describe("addThreads", () => {
      let addItemsSpy: MockInstance<
        typeof client.api.annotationQueues.addItemsToAnnotationQueue
      >;

      beforeEach(() => {
        addItemsSpy = vi
          .spyOn(client.api.annotationQueues, "addItemsToAnnotationQueue")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        addItemsSpy.mockRestore();
      });

      it("should extract threadModelId and call API correctly", async () => {
        const queue = new ThreadsAnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        const threads: OpikApi.TraceThread[] = [
          { threadModelId: "thread-model-1" },
          { threadModelId: "thread-model-2" },
        ];

        addItemsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.addThreads(threads);

        expect(addItemsSpy).toHaveBeenCalledWith("queue-id", {
          body: { ids: ["thread-model-1", "thread-model-2"] },
        });
      });

      it("should handle empty array (no-op)", async () => {
        const queue = new ThreadsAnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        await queue.addThreads([]);

        expect(addItemsSpy).not.toHaveBeenCalled();
      });

      it("should batch large arrays (>1000 items)", async () => {
        const queue = new ThreadsAnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        const threads: OpikApi.TraceThread[] = Array.from(
          { length: 1500 },
          (_, i) => ({
            threadModelId: `thread-model-${i}`,
          })
        );

        addItemsSpy.mockImplementation(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.addThreads(threads);

        expect(addItemsSpy).toHaveBeenCalledTimes(2);
        expect(addItemsSpy.mock.calls[0][1].body.ids).toHaveLength(1000);
        expect(addItemsSpy.mock.calls[1][1].body.ids).toHaveLength(500);
      });

      it("should throw AnnotationQueueItemMissingIdError when thread has no threadModelId", async () => {
        const queue = new ThreadsAnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        const threads: OpikApi.TraceThread[] = [
          { threadModelId: "thread-model-1" },
          {} as OpikApi.TraceThread,
        ];

        await expect(queue.addThreads(threads)).rejects.toThrow(
          AnnotationQueueItemMissingIdError
        );
        expect(addItemsSpy).not.toHaveBeenCalled();
      });
    });

    describe("removeThreads", () => {
      let removeItemsSpy: MockInstance<
        typeof client.api.annotationQueues.removeItemsFromAnnotationQueue
      >;

      beforeEach(() => {
        removeItemsSpy = vi
          .spyOn(client.api.annotationQueues, "removeItemsFromAnnotationQueue")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        removeItemsSpy.mockRestore();
      });

      it("should extract threadModelId and call API correctly", async () => {
        const queue = new ThreadsAnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        const threads: OpikApi.TraceThread[] = [
          { threadModelId: "thread-model-1" },
          { threadModelId: "thread-model-2" },
        ];

        removeItemsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.removeThreads(threads);

        expect(removeItemsSpy).toHaveBeenCalledWith("queue-id", {
          body: { ids: ["thread-model-1", "thread-model-2"] },
        });
      });
    });
  });
});
