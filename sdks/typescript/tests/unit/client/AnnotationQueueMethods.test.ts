import { Opik } from "opik";
import { MockInstance } from "vitest";
import { TracesAnnotationQueue, ThreadsAnnotationQueue } from "@/annotation-queue";
import { AnnotationQueueNotFoundError } from "@/annotation-queue/errors";
import { OpikApiError } from "@/rest_api";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";

describe("OpikClient Annotation Queue Methods", () => {
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

  describe("createTracesAnnotationQueue", () => {
    let createSpy: MockInstance<
      typeof client.api.annotationQueues.createAnnotationQueue
    >;
    let retrieveProjectSpy: MockInstance<
      typeof client.api.projects.retrieveProject
    >;

    beforeEach(() => {
      createSpy = vi
        .spyOn(client.api.annotationQueues, "createAnnotationQueue")
        .mockImplementation(mockAPIFunction);
      retrieveProjectSpy = vi
        .spyOn(client.api.projects, "retrieveProject")
        .mockImplementation(mockAPIFunction);
    });

    afterEach(() => {
      createSpy.mockRestore();
      retrieveProjectSpy.mockRestore();
    });

    it("should call API and return TracesAnnotationQueue instance", async () => {
      retrieveProjectSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          id: "project-id",
          name: "opik-sdk-typescript",
        })
      );

      createSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      const queue = await client.createTracesAnnotationQueue({
        name: "Test Traces Queue",
        description: "Test description",
      });

      expect(queue).toBeInstanceOf(TracesAnnotationQueue);
      expect(queue.name).toBe("Test Traces Queue");
      expect(queue.scope).toBe("trace");
      expect(queue.description).toBe("Test description");
      expect(createSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Test Traces Queue",
          scope: "trace",
          description: "Test description",
          projectId: "project-id",
        })
      );
    });
  });

  describe("createThreadsAnnotationQueue", () => {
    let createSpy: MockInstance<
      typeof client.api.annotationQueues.createAnnotationQueue
    >;
    let retrieveProjectSpy: MockInstance<
      typeof client.api.projects.retrieveProject
    >;

    beforeEach(() => {
      createSpy = vi
        .spyOn(client.api.annotationQueues, "createAnnotationQueue")
        .mockImplementation(mockAPIFunction);
      retrieveProjectSpy = vi
        .spyOn(client.api.projects, "retrieveProject")
        .mockImplementation(mockAPIFunction);
    });

    afterEach(() => {
      createSpy.mockRestore();
      retrieveProjectSpy.mockRestore();
    });

    it("should call API and return ThreadsAnnotationQueue instance", async () => {
      retrieveProjectSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          id: "project-id",
          name: "opik-sdk-typescript",
        })
      );

      createSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      const queue = await client.createThreadsAnnotationQueue({
        name: "Test Threads Queue",
        description: "Test description",
      });

      expect(queue).toBeInstanceOf(ThreadsAnnotationQueue);
      expect(queue.name).toBe("Test Threads Queue");
      expect(queue.scope).toBe("thread");
      expect(queue.description).toBe("Test description");
      expect(createSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "Test Threads Queue",
          scope: "thread",
          description: "Test description",
          projectId: "project-id",
        })
      );
    });
  });

  describe("getTracesAnnotationQueue", () => {
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

    it("should fetch by ID and return TracesAnnotationQueue instance", async () => {
      getByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          id: "queue-id",
          name: "Test Traces Queue",
          projectId: "project-id",
          scope: "trace",
        })
      );

      const queue = await client.getTracesAnnotationQueue("queue-id");

      expect(queue).toBeInstanceOf(TracesAnnotationQueue);
      expect(queue.id).toBe("queue-id");
      expect(queue.name).toBe("Test Traces Queue");
      expect(queue.scope).toBe("trace");
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
        client.getTracesAnnotationQueue("non-existent-id")
      ).rejects.toThrow(AnnotationQueueNotFoundError);
    });

    it("should throw error if queue is not a traces queue", async () => {
      getByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          id: "queue-id",
          name: "Thread Queue",
          projectId: "project-id",
          scope: "thread",
        })
      );

      await expect(
        client.getTracesAnnotationQueue("queue-id")
      ).rejects.toThrow("is not a trace queue");
    });
  });

  describe("getThreadsAnnotationQueue", () => {
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

    it("should fetch by ID and return ThreadsAnnotationQueue instance", async () => {
      getByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          id: "queue-id",
          name: "Test Threads Queue",
          projectId: "project-id",
          scope: "thread",
        })
      );

      const queue = await client.getThreadsAnnotationQueue("queue-id");

      expect(queue).toBeInstanceOf(ThreadsAnnotationQueue);
      expect(queue.id).toBe("queue-id");
      expect(queue.name).toBe("Test Threads Queue");
      expect(queue.scope).toBe("thread");
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
        client.getThreadsAnnotationQueue("non-existent-id")
      ).rejects.toThrow(AnnotationQueueNotFoundError);
    });

    it("should throw error if queue is not a threads queue", async () => {
      getByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          id: "queue-id",
          name: "Trace Queue",
          projectId: "project-id",
          scope: "trace",
        })
      );

      await expect(
        client.getThreadsAnnotationQueue("queue-id")
      ).rejects.toThrow("is not a thread queue");
    });
  });

  describe("getTracesAnnotationQueues", () => {
    let findSpy: MockInstance<
      typeof client.api.annotationQueues.findAnnotationQueues
    >;

    beforeEach(() => {
      findSpy = vi
        .spyOn(client.api.annotationQueues, "findAnnotationQueues")
        .mockImplementation(mockAPIFunction);
    });

    afterEach(() => {
      findSpy.mockRestore();
    });

    it("should return array of TracesAnnotationQueue instances", async () => {
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
              scope: "trace",
            },
          ],
        })
      );

      const queues = await client.getTracesAnnotationQueues();

      expect(queues).toHaveLength(2);
      expect(queues[0]).toBeInstanceOf(TracesAnnotationQueue);
      expect(queues[0].name).toBe("Queue 1");
      expect(queues[1].name).toBe("Queue 2");
    });

    it("should filter by scope", async () => {
      findSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({ content: [] })
      );

      await client.getTracesAnnotationQueues();

      expect(findSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: expect.stringContaining('"scope"'),
        })
      );
    });
  });

  describe("getThreadsAnnotationQueues", () => {
    let findSpy: MockInstance<
      typeof client.api.annotationQueues.findAnnotationQueues
    >;

    beforeEach(() => {
      findSpy = vi
        .spyOn(client.api.annotationQueues, "findAnnotationQueues")
        .mockImplementation(mockAPIFunction);
    });

    afterEach(() => {
      findSpy.mockRestore();
    });

    it("should return array of ThreadsAnnotationQueue instances", async () => {
      findSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          content: [
            {
              id: "queue-1",
              name: "Queue 1",
              projectId: "project-id",
              scope: "thread",
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

      const queues = await client.getThreadsAnnotationQueues();

      expect(queues).toHaveLength(2);
      expect(queues[0]).toBeInstanceOf(ThreadsAnnotationQueue);
      expect(queues[0].name).toBe("Queue 1");
      expect(queues[1].name).toBe("Queue 2");
    });
  });

  describe("deleteTracesAnnotationQueue", () => {
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

      await client.deleteTracesAnnotationQueue("queue-id");

      expect(deleteSpy).toHaveBeenCalledWith({ ids: ["queue-id"] });
    });
  });

  describe("deleteThreadsAnnotationQueue", () => {
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

      await client.deleteThreadsAnnotationQueue("queue-id");

      expect(deleteSpy).toHaveBeenCalledWith({ ids: ["queue-id"] });
    });
  });
});
