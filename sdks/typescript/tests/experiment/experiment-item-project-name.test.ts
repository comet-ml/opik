import { vi, describe, it, expect, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { OpikClient } from "@/client/Client";
import { Experiment } from "@/experiment/Experiment";
import { ExperimentItemReferences } from "@/experiment/ExperimentItem";
import { mockAPIFunction } from "../mockUtils";

/**
 * Tests to verify project_name is correctly passed through experiment item creation.
 * Similar to Python SDK test_experiment_item_project_name.py
 */
describe("ExperimentItem project_name handling", () => {
  let opikClient: OpikClient;
  let experiment: Experiment;
  let createExperimentItemsSpy: MockInstance;

  beforeEach(() => {
    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    experiment = new Experiment(
      {
        id: "test-experiment-id",
        name: "test-experiment",
        datasetName: "test-dataset",
      },
      opikClient
    );

    createExperimentItemsSpy = vi
      .spyOn(opikClient.api.experiments, "createExperimentItems")
      .mockImplementation(mockAPIFunction);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("ExperimentItemReferences", () => {
    it("should create ExperimentItemReferences with project_name", () => {
      const references = new ExperimentItemReferences({
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
        projectName: "test-project",
      });

      expect(references.datasetItemId).toBe("dataset-item-1");
      expect(references.traceId).toBe("trace-1");
      expect(references.projectName).toBe("test-project");
    });

    it("should create ExperimentItemReferences without project_name (optional)", () => {
      const references = new ExperimentItemReferences({
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
      });

      expect(references.datasetItemId).toBe("dataset-item-1");
      expect(references.traceId).toBe("trace-1");
      expect(references.projectName).toBeUndefined();
    });

    it("should accept undefined project_name explicitly", () => {
      const references = new ExperimentItemReferences({
        datasetItemId: "dataset-item-1",
        traceId: "trace-1",
        projectName: undefined,
      });

      expect(references.projectName).toBeUndefined();
    });
  });

  describe("Experiment.insert with project_name", () => {
    it("should pass project_name when creating experiment items", async () => {
      const references = [
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-1",
          traceId: "trace-1",
          projectName: "my-project",
        }),
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-2",
          traceId: "trace-2",
          projectName: "my-project",
        }),
      ];

      await experiment.insert(references);

      expect(createExperimentItemsSpy).toHaveBeenCalledWith({
        experimentItems: expect.arrayContaining([
          expect.objectContaining({
            experimentId: experiment.id,
            datasetItemId: "dataset-item-1",
            traceId: "trace-1",
            projectName: "my-project",
          }),
          expect.objectContaining({
            experimentId: experiment.id,
            datasetItemId: "dataset-item-2",
            traceId: "trace-2",
            projectName: "my-project",
          }),
        ]),
      });
    });

    it("should pass undefined project_name when not provided", async () => {
      const references = [
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-1",
          traceId: "trace-1",
        }),
      ];

      await experiment.insert(references);

      expect(createExperimentItemsSpy).toHaveBeenCalledWith({
        experimentItems: expect.arrayContaining([
          expect.objectContaining({
            experimentId: experiment.id,
            datasetItemId: "dataset-item-1",
            traceId: "trace-1",
            projectName: undefined,
          }),
        ]),
      });
    });

    it("should handle mixed project_name values", async () => {
      const references = [
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-1",
          traceId: "trace-1",
          projectName: "project-a",
        }),
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-2",
          traceId: "trace-2",
          projectName: undefined,
        }),
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-3",
          traceId: "trace-3",
          projectName: "project-b",
        }),
      ];

      await experiment.insert(references);

      const calls = createExperimentItemsSpy.mock.calls[0][0];
      expect(calls.experimentItems[0].projectName).toBe("project-a");
      expect(calls.experimentItems[1].projectName).toBeUndefined();
      expect(calls.experimentItems[2].projectName).toBe("project-b");
    });
  });

  describe("Backward compatibility", () => {
    it("should work without project_name (backward compatible)", async () => {
      const references = [
        new ExperimentItemReferences({
          datasetItemId: "dataset-item-1",
          traceId: "trace-1",
        }),
      ];

      await experiment.insert(references);

      expect(createExperimentItemsSpy).toHaveBeenCalledTimes(1);
      expect(createExperimentItemsSpy).toHaveBeenCalledWith({
        experimentItems: expect.arrayContaining([
          expect.objectContaining({
            datasetItemId: "dataset-item-1",
            traceId: "trace-1",
          }),
        ]),
      });
    });
  });
});
