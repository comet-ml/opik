import { Opik } from "opik";
import { MockInstance } from "vitest";
import { AnnotationQueue } from "@/annotation-queue";
import {
  AnnotationQueueNotFoundError,
  AnnotationQueueScopeMismatchError,
  AnnotationQueueItemMissingIdError,
} from "@/annotation-queue/errors";
import { OpikApiError } from "@/rest_api";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";
import * as OpikApi from "@/rest_api/api";

describe("AnnotationQueue", () => {
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
    loggerDebugSpy.mockRestore();
    loggerErrorSpy.mockRestore();
  });

  describe("AnnotationQueue class", () => {
    describe("constructor and properties", () => {
      it("should create instance with all properties correctly assigned", () => {
        const data: OpikApi.AnnotationQueuePublic = {
          id: "queue-id",
          name: "Test Queue",
          projectId: "project-id",
          scope: "trace",
          description: "Test description",
          instructions: "Test instructions",
          commentsEnabled: true,
          feedbackDefinitionNames: ["metric1", "metric2"],
          itemsCount: 10,
        };

        const queue = new AnnotationQueue(data, client);

        expect(queue.id).toBe("queue-id");
        expect(queue.name).toBe("Test Queue");
        expect(queue.projectId).toBe("project-id");
        expect(queue.scope).toBe("trace");
        expect(queue.description).toBe("Test description");
        expect(queue.instructions).toBe("Test instructions");
        expect(queue.commentsEnabled).toBe(true);
        expect(queue.feedbackDefinitionNames).toEqual(["metric1", "metric2"]);
        expect(queue.itemsCount).toBe(10);
      });

      it("should handle optional properties when undefined", () => {
        const data: OpikApi.AnnotationQueuePublic = {
          id: "queue-id",
          name: "Minimal Queue",
          projectId: "project-id",
          scope: "thread",
        };

        const queue = new AnnotationQueue(data, client);

        expect(queue.id).toBe("queue-id");
        expect(queue.name).toBe("Minimal Queue");
        expect(queue.description).toBeUndefined();
        expect(queue.instructions).toBeUndefined();
        expect(queue.commentsEnabled).toBeUndefined();
        expect(queue.feedbackDefinitionNames).toBeUndefined();
        expect(queue.itemsCount).toBeUndefined();
      });
    });

    describe("update", () => {
      let updateSpy: MockInstance<
        typeof client.api.annotationQueues.updateAnnotationQueue
      >;

      beforeEach(() => {
        updateSpy = vi
          .spyOn(client.api.annotationQueues, "updateAnnotationQueue")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        updateSpy.mockRestore();
      });

      it("should call REST API with correct parameters", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        updateSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.update({
          name: "Updated Name",
          description: "Updated description",
          instructions: "Updated instructions",
        });

        expect(updateSpy).toHaveBeenCalledWith("queue-id", {
          name: "Updated Name",
          description: "Updated description",
          instructions: "Updated instructions",
        });
        expect(loggerDebugSpy).toHaveBeenCalledWith(
          'Updating annotation queue "Test Queue"',
          expect.any(Object)
        );
      });

      it("should update only specified properties", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        updateSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.update({ description: "Only description" });

        expect(updateSpy).toHaveBeenCalledWith("queue-id", {
          description: "Only description",
        });
      });
    });

    describe("delete", () => {
      let deleteSpy: MockInstance<
        typeof client.api.annotationQueues.deleteAnnotationQueueBatch
      >;

      beforeEach(() => {
        deleteSpy = vi
          .spyOn(client.api.annotationQueues, "deleteAnnotationQueueBatch")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        deleteSpy.mockRestore();
      });

      it("should call REST API with correct queue ID", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        deleteSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.delete();

        expect(deleteSpy).toHaveBeenCalledWith({ ids: ["queue-id"] });
        expect(loggerDebugSpy).toHaveBeenCalledWith(
          'Deleting annotation queue "Test Queue"'
        );
      });
    });

    describe("addTraces", () => {
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

      it("should extract IDs and call API correctly", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        const traces: OpikApi.TracePublic[] = [
          { id: "trace-1", startTime: new Date() },
          { id: "trace-2", startTime: new Date() },
        ];

        addItemsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.addTraces(traces);

        expect(addItemsSpy).toHaveBeenCalledWith("queue-id", {
          body: { ids: ["trace-1", "trace-2"] },
        });
      });

      it("should handle empty array (no-op)", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        await queue.addTraces([]);

        expect(addItemsSpy).not.toHaveBeenCalled();
      });

      it("should batch large arrays (>1000 items)", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        const traces: OpikApi.TracePublic[] = Array.from(
          { length: 2500 },
          (_, i) => ({
            id: `trace-${i}`,
            startTime: new Date(),
          })
        );

        addItemsSpy.mockImplementation(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.addTraces(traces);

        expect(addItemsSpy).toHaveBeenCalledTimes(3);
        expect(addItemsSpy.mock.calls[0][1].body.ids).toHaveLength(1000);
        expect(addItemsSpy.mock.calls[1][1].body.ids).toHaveLength(1000);
        expect(addItemsSpy.mock.calls[2][1].body.ids).toHaveLength(500);
      });

      it("should throw AnnotationQueueScopeMismatchError on thread-scoped queue", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        const traces: OpikApi.TracePublic[] = [
          { id: "trace-1", startTime: new Date() },
        ];

        await expect(queue.addTraces(traces)).rejects.toThrow(
          AnnotationQueueScopeMismatchError
        );
        expect(addItemsSpy).not.toHaveBeenCalled();
      });

      it("should throw AnnotationQueueItemMissingIdError when trace has no ID", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        const traces: OpikApi.TracePublic[] = [
          { id: "trace-1", startTime: new Date() },
          { startTime: new Date() } as OpikApi.TracePublic,
        ];

        await expect(queue.addTraces(traces)).rejects.toThrow(
          AnnotationQueueItemMissingIdError
        );
        expect(addItemsSpy).not.toHaveBeenCalled();
      });

      it("should invalidate items count after adding traces", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
            itemsCount: 5,
          },
          client
        );

        expect(queue.itemsCount).toBe(5);

        addItemsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.addTraces([{ id: "trace-1", startTime: new Date() }]);

        expect(queue.itemsCount).toBeUndefined();
      });
    });

    describe("removeTraces", () => {
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

      it("should extract IDs and call API correctly", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        const traces: OpikApi.TracePublic[] = [
          { id: "trace-1", startTime: new Date() },
          { id: "trace-2", startTime: new Date() },
        ];

        removeItemsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await queue.removeTraces(traces);

        expect(removeItemsSpy).toHaveBeenCalledWith("queue-id", {
          body: { ids: ["trace-1", "trace-2"] },
        });
      });

      it("should handle empty array (no-op)", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        await queue.removeTraces([]);

        expect(removeItemsSpy).not.toHaveBeenCalled();
      });

      it("should throw AnnotationQueueScopeMismatchError on thread-scoped queue", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Thread Queue",
            projectId: "project-id",
            scope: "thread",
          },
          client
        );

        const traces: OpikApi.TracePublic[] = [
          { id: "trace-1", startTime: new Date() },
        ];

        await expect(queue.removeTraces(traces)).rejects.toThrow(
          AnnotationQueueScopeMismatchError
        );
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
        const queue = new AnnotationQueue(
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
        const queue = new AnnotationQueue(
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
        const queue = new AnnotationQueue(
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

      it("should throw AnnotationQueueScopeMismatchError on trace-scoped queue", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Trace Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        const threads: OpikApi.TraceThread[] = [
          { threadModelId: "thread-model-1" },
        ];

        await expect(queue.addThreads(threads)).rejects.toThrow(
          AnnotationQueueScopeMismatchError
        );
        expect(addItemsSpy).not.toHaveBeenCalled();
      });

      it("should throw AnnotationQueueItemMissingIdError when thread has no threadModelId", async () => {
        const queue = new AnnotationQueue(
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
        const queue = new AnnotationQueue(
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

      it("should throw AnnotationQueueScopeMismatchError on trace-scoped queue", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Trace Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        const threads: OpikApi.TraceThread[] = [
          { threadModelId: "thread-model-1" },
        ];

        await expect(queue.removeThreads(threads)).rejects.toThrow(
          AnnotationQueueScopeMismatchError
        );
      });
    });

    describe("refreshItemsCount", () => {
      let getByIdSpy: MockInstance<
        typeof client.api.annotationQueues.getAnnotationQueueById
      >;

      beforeEach(() => {
        getByIdSpy = vi
          .spyOn(client.api.annotationQueues, "getAnnotationQueueById")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        getByIdSpy.mockRestore();
      });

      it("should fetch and update items count from backend", async () => {
        const queue = new AnnotationQueue(
          {
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
          },
          client
        );

        expect(queue.itemsCount).toBeUndefined();

        getByIdSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
            itemsCount: 42,
          })
        );

        const count = await queue.refreshItemsCount();

        expect(count).toBe(42);
        expect(queue.itemsCount).toBe(42);
      });
    });
  });

  describe("OpikClient annotation queue factory methods", () => {
    describe("createAnnotationQueue", () => {
      let createSpy: MockInstance<
        typeof client.api.annotationQueues.createAnnotationQueue
      >;
      let findProjectsSpy: MockInstance<
        typeof client.api.projects.findProjects
      >;

      beforeEach(() => {
        createSpy = vi
          .spyOn(client.api.annotationQueues, "createAnnotationQueue")
          .mockImplementation(mockAPIFunction);
        findProjectsSpy = vi
          .spyOn(client.api.projects, "findProjects")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        createSpy.mockRestore();
        findProjectsSpy.mockRestore();
      });

      it("should call API and return AnnotationQueue instance", async () => {
        findProjectsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            content: [{ id: "project-id", name: "opik-sdk-typescript" }],
          })
        );

        createSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        const queue = await client.createAnnotationQueue({
          name: "Test Queue",
          scope: "trace",
          description: "Test description",
        });

        expect(queue).toBeInstanceOf(AnnotationQueue);
        expect(queue.name).toBe("Test Queue");
        expect(queue.scope).toBe("trace");
        expect(queue.description).toBe("Test description");
        expect(createSpy).toHaveBeenCalledWith(
          expect.objectContaining({
            name: "Test Queue",
            scope: "trace",
            description: "Test description",
            projectId: "project-id",
          })
        );
      });

      it("should use client's default project when not specified", async () => {
        findProjectsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            content: [{ id: "default-project-id", name: "opik-sdk-typescript" }],
          })
        );

        createSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await client.createAnnotationQueue({
          name: "Test Queue",
          scope: "trace",
        });

        expect(findProjectsSpy).toHaveBeenCalledWith({
          name: "opik-sdk-typescript",
          size: 1,
        });
      });

      it("should use specified project name", async () => {
        findProjectsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            content: [{ id: "custom-project-id", name: "custom-project" }],
          })
        );

        createSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await client.createAnnotationQueue({
          name: "Test Queue",
          scope: "trace",
          projectName: "custom-project",
        });

        expect(findProjectsSpy).toHaveBeenCalledWith({
          name: "custom-project",
          size: 1,
        });
      });
    });

    describe("getAnnotationQueue", () => {
      let getByIdSpy: MockInstance<
        typeof client.api.annotationQueues.getAnnotationQueueById
      >;

      beforeEach(() => {
        getByIdSpy = vi
          .spyOn(client.api.annotationQueues, "getAnnotationQueueById")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        getByIdSpy.mockRestore();
      });

      it("should fetch by ID and return AnnotationQueue instance", async () => {
        getByIdSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            id: "queue-id",
            name: "Test Queue",
            projectId: "project-id",
            scope: "trace",
            itemsCount: 10,
          })
        );

        const queue = await client.getAnnotationQueue("queue-id");

        expect(queue).toBeInstanceOf(AnnotationQueue);
        expect(queue.id).toBe("queue-id");
        expect(queue.name).toBe("Test Queue");
        expect(queue.itemsCount).toBe(10);
        expect(getByIdSpy).toHaveBeenCalledWith("queue-id");
      });

      it("should throw AnnotationQueueNotFoundError on 404", async () => {
        getByIdSpy.mockImplementationOnce(() => {
          throw new OpikApiError({
            message: "Not found",
            statusCode: 404,
          });
        });

        await expect(
          client.getAnnotationQueue("non-existent-id")
        ).rejects.toThrow(AnnotationQueueNotFoundError);
      });

      it("should propagate other errors", async () => {
        const apiError = new OpikApiError({
          message: "Server error",
          statusCode: 500,
        });

        getByIdSpy.mockImplementationOnce(() => {
          throw apiError;
        });

        await expect(client.getAnnotationQueue("queue-id")).rejects.toThrow(
          apiError
        );
      });
    });

    describe("getAnnotationQueues", () => {
      let findSpy: MockInstance<
        typeof client.api.annotationQueues.findAnnotationQueues
      >;
      let findProjectsSpy: MockInstance<
        typeof client.api.projects.findProjects
      >;

      beforeEach(() => {
        findSpy = vi
          .spyOn(client.api.annotationQueues, "findAnnotationQueues")
          .mockImplementation(mockAPIFunction);
        findProjectsSpy = vi
          .spyOn(client.api.projects, "findProjects")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        findSpy.mockRestore();
        findProjectsSpy.mockRestore();
      });

      it("should return array of AnnotationQueue instances", async () => {
        findSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            content: [
              {
                id: "queue-1",
                name: "Queue 1",
                projectId: "project-id",
                scope: "trace",
              },
              {
                id: "queue-2",
                name: "Queue 2",
                projectId: "project-id",
                scope: "thread",
              },
            ],
          })
        );

        const queues = await client.getAnnotationQueues();

        expect(queues).toHaveLength(2);
        expect(queues[0]).toBeInstanceOf(AnnotationQueue);
        expect(queues[0].name).toBe("Queue 1");
        expect(queues[1].name).toBe("Queue 2");
      });

      it("should filter by project name when specified", async () => {
        findProjectsSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({
            content: [{ id: "specific-project-id", name: "specific-project" }],
          })
        );

        findSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({ content: [] })
        );

        await client.getAnnotationQueues({ projectName: "specific-project" });

        expect(findProjectsSpy).toHaveBeenCalledWith({
          name: "specific-project",
          size: 1,
        });
        expect(findSpy).toHaveBeenCalledWith(
          expect.objectContaining({
            filters: expect.stringContaining("specific-project-id"),
          })
        );
      });

      it("should use maxResults parameter", async () => {
        findSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({ content: [] })
        );

        await client.getAnnotationQueues({ maxResults: 50 });

        expect(findSpy).toHaveBeenCalledWith(
          expect.objectContaining({ size: 50 })
        );
      });

      it("should return empty array when no queues found", async () => {
        findSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise({ content: [] })
        );

        const queues = await client.getAnnotationQueues();

        expect(queues).toEqual([]);
      });
    });

    describe("deleteAnnotationQueue", () => {
      let deleteSpy: MockInstance<
        typeof client.api.annotationQueues.deleteAnnotationQueueBatch
      >;

      beforeEach(() => {
        deleteSpy = vi
          .spyOn(client.api.annotationQueues, "deleteAnnotationQueueBatch")
          .mockImplementation(mockAPIFunction);
      });

      afterEach(() => {
        deleteSpy.mockRestore();
      });

      it("should call API with correct ID", async () => {
        deleteSpy.mockImplementationOnce(() =>
          createMockHttpResponsePromise(undefined)
        );

        await client.deleteAnnotationQueue("queue-id");

        expect(deleteSpy).toHaveBeenCalledWith({ ids: ["queue-id"] });
      });

      it("should propagate errors", async () => {
        const apiError = new OpikApiError({
          message: "Delete failed",
          statusCode: 500,
        });

        deleteSpy.mockImplementationOnce(() => {
          throw apiError;
        });

        await expect(
          client.deleteAnnotationQueue("queue-id")
        ).rejects.toThrow();

        expect(loggerErrorSpy).toHaveBeenCalled();
      });
    });
  });
});
