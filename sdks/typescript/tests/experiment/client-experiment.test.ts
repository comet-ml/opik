import { vi, describe, it, beforeEach, afterEach } from "vitest";
import type { MockInstance } from "vitest";
import type { TestContext } from "vitest";
import { Opik } from "opik";
import { OpikApiError } from "@/rest_api";
import { ExperimentPublicType } from "@/rest_api/api/types";
import {
  createMockHttpResponsePromise,
  mockAPIFunctionWithStream,
  mockAPIFunction,
} from "../mockUtils";
import { ExperimentNotFoundError } from "@/errors/experiment/errors";
import { createMockExperiment, verifyExperiment } from "./utils";

interface ExperimentTestContext extends TestContext {
  client: Opik;
  spies: {
    findExperiments: MockInstance;
    streamExperiments: MockInstance;
    createExperiment: MockInstance;
    deleteExperiments: MockInstance;
    updateExperiment: MockInstance;
    getDatasetByIdentifier: MockInstance;
  };
}

describe("Opik experiment operations", () => {
  beforeEach<ExperimentTestContext>((ctx) => {
    vi.useFakeTimers({ shouldAdvanceTime: true });

    const client = new Opik({
      projectName: "opik-sdk-typescript",
      workspaceName: "test-workspace",
    });

    const spies = {
      findExperiments: vi
        .spyOn(client.api.experiments, "findExperiments")
        .mockImplementation(() => createMockHttpResponsePromise({})),

      streamExperiments: vi
        .spyOn(client.api.experiments, "streamExperiments")
        .mockImplementation(() => mockAPIFunctionWithStream("[]")),

      createExperiment: vi
        .spyOn(client.api.experiments, "createExperiment")
        .mockImplementation(mockAPIFunction),

      deleteExperiments: vi
        .spyOn(client.api.experiments, "deleteExperimentsById")
        .mockImplementation(mockAPIFunction),

      updateExperiment: vi
        .spyOn(client.api.experiments, "updateExperiment")
        .mockImplementation(() => createMockHttpResponsePromise(undefined)),

      getDatasetByIdentifier: vi
        .spyOn(client.api.datasets, "getDatasetByIdentifier")
        .mockImplementation(mockAPIFunction),
    };

    ctx.client = client;
    ctx.spies = spies;
  });

  afterEach(async () => {
    await vi.runAllTimersAsync();
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  describe("getExperiment", () => {
    it<ExperimentTestContext>("should retrieve an existing experiment by name", async ({
      client,
      spies,
      expect,
    }) => {
      const mockExperiment = createMockExperiment();
      const mockNdjsonData = JSON.stringify(mockExperiment);

      spies.streamExperiments.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(mockNdjsonData)
      );

      const result = await client.getExperiment(mockExperiment.name!);

      verifyExperiment(result, mockExperiment);
      expect(spies.streamExperiments).toHaveBeenCalledWith({
        name: mockExperiment.name,
      });
    });

    it<ExperimentTestContext>("should throw ExperimentNotFoundError when experiment doesn't exist", async ({
      client,
      spies,
      expect,
    }) => {
      spies.streamExperiments.mockImplementationOnce(() =>
        mockAPIFunctionWithStream("")
      );

      await expect(
        client.getExperiment("nonexistent-experiment")
      ).rejects.toThrow(ExperimentNotFoundError);

      expect(spies.streamExperiments).toHaveBeenCalledWith({
        name: "nonexistent-experiment",
      });
    });

    it<ExperimentTestContext>("should propagate other API errors", async ({
      client,
      spies,
      expect,
    }) => {
      const apiError = new OpikApiError({
        message: "Server error",
        statusCode: 500,
      });

      spies.streamExperiments.mockRejectedValueOnce(apiError);

      await expect(client.getExperiment("test-experiment")).rejects.toThrow(
        OpikApiError
      );
      expect(spies.streamExperiments).toHaveBeenCalledWith({
        name: "test-experiment",
      });
    });

    it<ExperimentTestContext>("should return the first experiment when multiple matches exist", async ({
      client,
      spies,
      expect,
    }) => {
      const firstExperiment = createMockExperiment({ id: "exp-1" });
      const secondExperiment = createMockExperiment({ id: "exp-2" });

      const mockNdjsonData =
        JSON.stringify(firstExperiment) +
        "\n" +
        JSON.stringify(secondExperiment);

      spies.streamExperiments.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(mockNdjsonData)
      );

      const result = await client.getExperiment("test-experiment");

      verifyExperiment(result, firstExperiment);
      expect(spies.streamExperiments).toHaveBeenCalledWith({
        name: "test-experiment",
      });
    });
  });

  describe("createExperiment", () => {
    it<ExperimentTestContext>("should create a new experiment with name and dataset name", async ({
      client,
      spies,
      expect,
    }) => {
      const mockExperimentId = "new-experiment-id-123";
      spies.createExperiment.mockImplementationOnce(() => {
        return createMockHttpResponsePromise({ id: mockExperimentId });
      });

      const result = await client.createExperiment({
        name: "new-experiment",
        datasetName: "test-dataset-name",
      });

      expect(result.name).toBe("new-experiment");
      expect(result.datasetName).toBe("test-dataset-name");

      expect(spies.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "new-experiment",
          datasetName: "test-dataset-name",
          type: "regular",
        })
      );
    });

    it<ExperimentTestContext>("should create a new experiment with a custom experiment type", async ({
      client,
      spies,
      expect,
    }) => {
      const mockExperimentId = "custom-experiment-id-456";
      spies.createExperiment.mockImplementationOnce(() => {
        return createMockHttpResponsePromise({ id: mockExperimentId });
      });

      const result = await client.createExperiment({
        name: "new-experiment",
        datasetName: "test-dataset-name",
        type: ExperimentPublicType.MiniBatch,
      });

      expect(result.name).toBe("new-experiment");
      expect(result.datasetName).toBe("test-dataset-name");

      expect(spies.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "new-experiment",
          datasetName: "test-dataset-name",
          type: ExperimentPublicType.MiniBatch,
        })
      );
    });

    it<ExperimentTestContext>("should create a new experiment with the MiniBatch type", async ({
      client,
      spies,
      expect,
    }) => {
      const mockExperimentId = "custom-experiment-id-456";
      spies.createExperiment.mockImplementationOnce(() => {
        return createMockHttpResponsePromise({ id: mockExperimentId });
      });

      const expData = {
        name: "custom-experiment",
        datasetName: "test-dataset",
        type: ExperimentPublicType.MiniBatch,
      };

      const result = await client.createExperiment(expData);

      expect(result.name).toBe("custom-experiment");
      expect(result.datasetName).toBe("test-dataset");
    });

    it.skip<ExperimentTestContext>("should handle API errors when creating experiments", async ({
      client,
      spies,
      expect,
    }) => {
      const errorMessage = "Failed to create experiment";
      const error = new Error(errorMessage);

      spies.createExperiment.mockRejectedValueOnce(error);

      await expect(
        client.createExperiment({
          name: "error-experiment",
          datasetName: "error-dataset",
        })
      ).rejects.toThrow(errorMessage);

      expect(spies.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({
          name: "error-experiment",
          datasetName: "error-dataset",
          type: ExperimentPublicType.Regular,
        })
      );
    });

    it<ExperimentTestContext>("should handle missing dataset name", async ({
      client,
      expect,
    }) => {
      await expect(
        // @ts-expect-error missing required property
        client.createExperiment({
          name: "test-experiment",
        })
      ).rejects.toThrow();
    });
  });

  describe("getExperimentsByName", () => {
    it<ExperimentTestContext>("should retrieve all experiments with the given name", async ({
      client,
      spies,
      expect,
    }) => {
      const mockExperiments = [
        createMockExperiment({
          id: "experiment-1",
          name: "experiment-one",
        }),
        createMockExperiment({
          id: "experiment-2",
          name: "experiment-one",
        }),
      ];

      const mockNdjsonData = mockExperiments
        .map((exp) => JSON.stringify(exp))
        .join("\n");
      spies.streamExperiments.mockImplementationOnce(() =>
        mockAPIFunctionWithStream(mockNdjsonData)
      );

      const results = await client.getExperimentsByName("experiment-one");

      expect(results).toHaveLength(2);
      verifyExperiment(results[0], mockExperiments[0]);
      verifyExperiment(results[1], mockExperiments[1]);
      expect(spies.streamExperiments).toHaveBeenCalledWith({
        name: "experiment-one",
      });
    });

    it<ExperimentTestContext>("should return an empty array when no experiments found", async ({
      client,
      spies,
      expect,
    }) => {
      spies.streamExperiments.mockImplementationOnce(() =>
        mockAPIFunctionWithStream("")
      );

      const results = await client.getExperimentsByName(
        "nonexistent-experiment"
      );

      expect(results).toEqual([]);
      expect(spies.streamExperiments).toHaveBeenCalledWith({
        name: "nonexistent-experiment",
      });
    });

    it<ExperimentTestContext>("should handle undefined content in response", async ({
      client,
      spies,
      expect,
    }) => {
      spies.streamExperiments.mockImplementationOnce(() =>
        mockAPIFunctionWithStream("null")
      );

      const results = await client.getExperimentsByName("experiment-1");

      expect(results).toEqual([]);
    });

    it<ExperimentTestContext>("should handle API errors", async ({
      client,
      spies,
      expect,
    }) => {
      const errorMessage = "fetch failed";
      const error = new Error(errorMessage);

      spies.streamExperiments.mockRejectedValueOnce(error);

      await expect(client.getExperimentsByName("experiment-1")).rejects.toThrow(
        "fetch failed"
      );
    });
  });

  describe("deleteExperiment", () => {
    it<ExperimentTestContext>("should delete an experiment by ID", async ({
      client,
      spies,
      expect,
    }) => {
      const experimentId = "experiment-to-delete-id";

      await client.deleteExperiment(experimentId);

      expect(spies.deleteExperiments).toHaveBeenCalledWith({
        ids: [experimentId],
      });
    });

    it<ExperimentTestContext>("should handle errors during the delete operation", async ({
      client,
      spies,
      expect,
    }) => {
      const experimentId = "test-experiment";

      spies.deleteExperiments.mockImplementationOnce(() => {
        throw new Error("Failed to delete experiment");
      });

      await expect(client.deleteExperiment(experimentId)).rejects.toThrow(
        "Failed to delete experiment"
      );

      expect(spies.deleteExperiments).toHaveBeenCalledWith({
        ids: [experimentId],
      });
    });
  });

  describe("getDatasetExperiments", () => {
    it<ExperimentTestContext>("should retrieve all experiments for a dataset", async ({
      client,
      spies,
      expect,
    }) => {
      const datasetId = "dataset-123";
      spies.getDatasetByIdentifier.mockImplementationOnce(() =>
        createMockHttpResponsePromise({ id: datasetId, name: "test-dataset" })
      );

      const mockExperiments = [
        createMockExperiment({
          id: "experiment-1",
          dataset_name: "test-dataset",
        }),
        createMockExperiment({
          id: "experiment-2",
          dataset_name: "test-dataset",
        }),
      ];

      spies.findExperiments.mockImplementationOnce(() =>
        createMockHttpResponsePromise({ content: mockExperiments })
      );

      const results = await client.getDatasetExperiments("test-dataset");

      expect(spies.getDatasetByIdentifier).toHaveBeenCalledWith({
        datasetName: "test-dataset",
      });
      expect(spies.findExperiments).toHaveBeenCalledWith({
        page: 1,
        size: 100,
        datasetId,
      });
      expect(results).toHaveLength(2);
      expect(results[0].id).toBe("experiment-1");
      expect(results[1].id).toBe("experiment-2");
    });

    it<ExperimentTestContext>("should handle pagination when there are more experiments than the limit", async ({
      client,
      spies,
      expect,
    }) => {
      const datasetId = "dataset-123";
      spies.getDatasetByIdentifier.mockImplementationOnce(() =>
        createMockHttpResponsePromise({ id: datasetId, name: "test-dataset" })
      );

      const page1Experiments = Array.from({ length: 20 }, (_, i) =>
        createMockExperiment({ id: `exp-${i}`, dataset_name: "test-dataset" })
      );
      const page2Experiments = Array.from({ length: 10 }, (_, i) =>
        createMockExperiment({
          id: `exp-${i + 20}`,
          dataset_name: "test-dataset",
        })
      );

      spies.findExperiments.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          content: page1Experiments,
        })
      );

      spies.findExperiments.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          content: page2Experiments,
        })
      );

      spies.findExperiments.mockImplementationOnce(() =>
        createMockHttpResponsePromise({
          content: [],
        })
      );

      const results = await client.getDatasetExperiments("test-dataset", 25);

      expect(spies.getDatasetByIdentifier).toHaveBeenCalledWith({
        datasetName: "test-dataset",
      });
      expect(spies.findExperiments).toHaveBeenCalledTimes(2);
      expect(spies.findExperiments).toHaveBeenNthCalledWith(1, {
        page: 1,
        size: 25,
        datasetId,
      });
      expect(spies.findExperiments).toHaveBeenNthCalledWith(2, {
        page: 2,
        size: 25,
        datasetId,
      });
      expect(results).toHaveLength(25);
    });

    it<ExperimentTestContext>("should handle dataset not found error", async ({
      client,
      spies,
      expect,
    }) => {
      const error = new Error("Dataset not found");
      spies.getDatasetByIdentifier.mockImplementationOnce(() => {
        throw error;
      });

      await expect(
        client.getDatasetExperiments("nonexistent-dataset")
      ).rejects.toThrow("Dataset not found");
    });

    it<ExperimentTestContext>("should handle API errors when getting dataset experiments", async ({
      client,
      spies,
      expect,
    }) => {
      const datasetId = "dataset-123";
      spies.getDatasetByIdentifier.mockImplementationOnce(() =>
        createMockHttpResponsePromise({ id: datasetId, name: "test-dataset" })
      );

      const apiError = new OpikApiError({
        message: "API error",
        statusCode: 500,
      });
      spies.findExperiments.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(
        client.getDatasetExperiments("test-dataset")
      ).rejects.toThrow(apiError);
    });
  });

  describe("updateExperiment", () => {
    it<ExperimentTestContext>("should update an experiment by ID", async ({
      client,
      spies,
      expect,
    }) => {
      const experimentId = "experiment-to-update-id";

      await client.updateExperiment(experimentId, {
        name: "updated-name",
        experimentConfig: { k: "v" },
      });

      expect(spies.updateExperiment).toHaveBeenCalledWith(experimentId, {
        name: "updated-name",
        metadata: { k: "v" },
      });
    });

    it<ExperimentTestContext>("should update only the name when experimentConfig is not provided", async ({
      client,
      spies,
      expect,
    }) => {
      const experimentId = "experiment-to-update-name-only";

      await client.updateExperiment(experimentId, { name: "new-name-only" });

      const callArgs = spies.updateExperiment.mock.calls[0];
      expect(callArgs[0]).toBe(experimentId);
      expect(callArgs[1]).toEqual({ name: "new-name-only" });
      expect(callArgs[1]).not.toHaveProperty("metadata");
    });

    it<ExperimentTestContext>("should update only the configuration when name is not provided", async ({
      client,
      spies,
      expect,
    }) => {
      const experimentId = "experiment-to-update-config-only";
      const newConfig = { model: "gpt-4", temperature: 0.7 };

      await client.updateExperiment(experimentId, {
        experimentConfig: newConfig,
      });

      const callArgs = spies.updateExperiment.mock.calls[0];
      expect(callArgs[0]).toBe(experimentId);
      expect(callArgs[1]).toEqual({ metadata: newConfig });
      expect(callArgs[1]).not.toHaveProperty("name");
    });

    it<ExperimentTestContext>("should throw error when id is empty string", async ({
      client,
      expect,
    }) => {
      await expect(
        client.updateExperiment("", { name: "new-name" })
      ).rejects.toThrow("id is required to update an experiment");
    });

    it<ExperimentTestContext>("should throw error when no parameters are provided", async ({
      client,
      expect,
    }) => {
      const experimentId = "experiment-to-update-no-params";

      await expect(
        client.updateExperiment(experimentId, {})
      ).rejects.toThrow("At least one of 'name' or 'experimentConfig' must be provided to update an experiment");
    });

    it<ExperimentTestContext>("should handle errors during the update operation", async ({
      client,
      spies,
      expect,
    }) => {
      const experimentId = "experiment-to-update-error";

      spies.updateExperiment.mockImplementationOnce(() => {
        throw new Error("Failed to update experiment");
      });

      await expect(
        client.updateExperiment(experimentId, {
          name: "bad-name",
          experimentConfig: {},
        })
      ).rejects.toThrow("Failed to update experiment");

      expect(spies.updateExperiment).toHaveBeenCalledWith(experimentId, {
        name: "bad-name",
        metadata: {},
      });
    });
  });
});
