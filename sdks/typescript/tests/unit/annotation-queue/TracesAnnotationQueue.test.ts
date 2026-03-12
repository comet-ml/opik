import { Opik } from "opik";
import { MockInstance } from "vitest";
import { TracesAnnotationQueue } from "@/annotation-queue";
import {
  AnnotationQueueItemMissingIdError,
} from "@/annotation-queue/errors";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";
import * as OpikApi from "@/rest_api/api";

describe("TracesAnnotationQueue", () => {
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

  describe("TracesAnnotationQueue class", () => {
    describe("constructor and properties", () => {
      it("should create instance with all properties correctly assigned", () => {
        const data: OpikApi.AnnotationQueuePublic = {
          id: "queue-id",
          name: "Test Traces Queue",
          projectId: "project-id",
          scope: "trace",
          description: "Test description",
          instructions: "Test instructions",
          commentsEnabled: true,
          feedbackDefinitionNames: ["metric1", "metric2"],
        };

        const queue = new TracesAnnotationQueue(data, client);

        expect(queue.id).toBe("queue-id");
        expect(queue.name).toBe("Test Traces Queue");
        expect(queue.projectId).toBe("project-id");
        expect(queue.scope).toBe("trace");
        expect(queue.description).toBe("Test description");
        expect(queue.instructions).toBe("Test instructions");
        expect(queue.commentsEnabled).toBe(true);
        expect(queue.feedbackDefinitionNames).toEqual(["metric1", "metric2"]);
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
        const queue = new TracesAnnotationQueue(
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
        const queue = new TracesAnnotationQueue(
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
        const queue = new TracesAnnotationQueue(
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

      it("should throw AnnotationQueueItemMissingIdError when trace has no ID", async () => {
        const queue = new TracesAnnotationQueue(
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
        const queue = new TracesAnnotationQueue(
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
        const queue = new TracesAnnotationQueue(
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
    });
  });
});
