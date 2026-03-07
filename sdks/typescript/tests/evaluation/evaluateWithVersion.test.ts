import { describe, test, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { evaluate } from "@/evaluation/evaluate";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { DatasetVersion } from "@/dataset/DatasetVersion";
import { EvaluationTask } from "@/evaluation/types";
import type { DatasetVersionPublic } from "opik";
import {
  createMockHttpResponsePromise,
  mockAPIFunction,
  mockAPIFunctionWithStream,
} from "../mockUtils";
import { ExactMatch } from "opik";

const MOCK_VERSION_INFO: DatasetVersionPublic = {
  id: "version-id-123",
  versionHash: "hash-abc",
  versionName: "v2",
  tags: ["test"],
  isLatest: true,
  itemsTotal: 10,
  itemsAdded: 5,
  itemsModified: 3,
  itemsDeleted: 1,
  changeDescription: "Test version",
  createdAt: new Date("2024-01-15T00:00:00Z"),
  createdBy: "test-user",
};

function createStreamItem(
  datasetItemId: string,
  data: Record<string, unknown>
): string {
  return JSON.stringify({ dataset_item_id: datasetItemId, data, source: "sdk" }) + "\n";
}

describe("evaluate with DatasetVersion", () => {
  let opikClient: OpikClient;
  let testDatasetVersion: DatasetVersion;
  let createExperimentSpy: MockInstance;
  let streamDatasetItemsSpy: MockInstance;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({ projectName: "opik-sdk-typescript-test" });

    const testDataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "test-dataset",
        description: "Test dataset for evaluation",
      },
      opikClient
    );

    testDatasetVersion = new DatasetVersion(
      "test-dataset",
      "test-dataset-id",
      MOCK_VERSION_INFO,
      opikClient
    );

    vi.spyOn(opikClient.api.traces, "createTraces").mockImplementation(
      mockAPIFunction
    );
    vi.spyOn(opikClient.api.spans, "createSpans").mockImplementation(
      mockAPIFunction
    );
    vi.spyOn(opikClient.api.traces, "scoreBatchOfTraces").mockImplementation(
      mockAPIFunction
    );
    vi.spyOn(opikClient.api.datasets, "getDatasetByIdentifier").mockImplementation(
      () => createMockHttpResponsePromise({ name: testDataset.name })
    );

    createExperimentSpy = vi
      .spyOn(opikClient.api.experiments, "createExperiment")
      .mockImplementation(mockAPIFunction);

    streamDatasetItemsSpy = vi
      .spyOn(opikClient.api.datasets, "streamDatasetItems")
      .mockImplementation(() =>
        mockAPIFunctionWithStream(
          createStreamItem("019c6c79-9e02-760b-aca7-2d8013169302", { input: "test input 1", expected: "test output 1" })
        )
      );

    vi.spyOn(opikClient.api.datasets, "listDatasetVersions").mockImplementation(
      () =>
        createMockHttpResponsePromise({
          content: [MOCK_VERSION_INFO],
          page: 1,
          size: 1,
          total: 1,
        })
    );

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("should accept DatasetVersion and pass version info to experiment", async () => {
    const mockTask: EvaluationTask = async () => ({ output: "generated output" });

    const result = await evaluate({
      dataset: testDatasetVersion,
      task: mockTask,
      experimentName: "test-version-experiment",
      client: opikClient,
    });

    expect(result).toBeDefined();
    expect(result.experimentName).toBe("test-version-experiment");
    expect(result.testResults).toHaveLength(1);

    expect(createExperimentSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "test-version-experiment",
        datasetName: "test-dataset",
        datasetVersionId: "version-id-123",
      })
    );

    expect(streamDatasetItemsSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        datasetName: "test-dataset",
        datasetVersion: "hash-abc",
      })
    );
  });

  test("should work with scoring metrics using DatasetVersion", async () => {
    const mockDatasetItemId = "019c6c79-9e03-760b-aca7-2d8013169303";
    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(
        createStreamItem(mockDatasetItemId, { input: "test input", expected: "exact match" })
      )
    );

    const mockTask: EvaluationTask = async () => ({ output: "exact match" });

    const result = await evaluate({
      dataset: testDatasetVersion,
      task: mockTask,
      experimentName: "test-metrics-experiment",
      scoringMetrics: [new ExactMatch("exact-match-metric")],
      client: opikClient,
    });

    expect(result.testResults).toHaveLength(1);
    expect(result.testResults[0].scoreResults).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          name: "exact-match-metric",
          value: 1,
        }),
      ])
    );
  });

  test("should process multiple items from DatasetVersion", async () => {
    const mockItemIds = [
      "019c6c79-9e04-760b-aca7-2d8013169304",
      "019c6c79-9e05-760b-aca7-2d8013169305",
      "019c6c79-9e06-760b-aca7-2d8013169306",
    ];
    const streamData = [
      createStreamItem(mockItemIds[0], { input: "input 1", expected: "output 1" }),
      createStreamItem(mockItemIds[1], { input: "input 2", expected: "output 2" }),
      createStreamItem(mockItemIds[2], { input: "input 3", expected: "output 3" }),
    ].join("");

    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(streamData)
    );

    const mockTask: EvaluationTask = async (input) => ({
      output: `processed ${input.input}`,
    });

    const result = await evaluate({
      dataset: testDatasetVersion,
      task: mockTask,
      experimentName: "test-multiple-items",
      client: opikClient,
    });

    expect(result.testResults).toHaveLength(3);
    expect(result.testResults[0].testCase.datasetItemId).toBe(mockItemIds[0]);
    expect(result.testResults[1].testCase.datasetItemId).toBe(mockItemIds[1]);
    expect(result.testResults[2].testCase.datasetItemId).toBe(mockItemIds[2]);
  });

  test("should respect nbSamples parameter with DatasetVersion", async () => {
    const mockTask: EvaluationTask = async () => ({ output: "generated output" });

    await evaluate({
      dataset: testDatasetVersion,
      task: mockTask,
      experimentName: "test-nbsamples",
      nbSamples: 5,
      client: opikClient,
    });

    expect(streamDatasetItemsSpy).toHaveBeenCalledWith(
      expect.objectContaining({ steamLimit: 5 })
    );
  });

  test("should handle DatasetVersion with experimentConfig", async () => {
    const mockTask: EvaluationTask = async () => ({ output: "generated output" });

    const experimentConfig = {
      model: "gpt-4",
      temperature: 0.7,
      version: "v2",
    };

    await evaluate({
      dataset: testDatasetVersion,
      task: mockTask,
      experimentName: "test-config",
      experimentConfig,
      client: opikClient,
    });

    expect(createExperimentSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: experimentConfig,
        datasetVersionId: "version-id-123",
      })
    );
  });
});
