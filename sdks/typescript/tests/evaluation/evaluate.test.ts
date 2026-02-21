import { describe, test, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { evaluate } from "@/evaluation/evaluate";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { EvaluationTask } from "@/evaluation/types";
import {
  createMockHttpResponsePromise,
  mockAPIFunction,
  mockAPIFunctionWithStream,
} from "../mockUtils";
import { SpanType } from "@/rest_api/api";
import { ExactMatch } from "opik";

describe("evaluate function", () => {
  // Client and mocks
  let opikClient: OpikClient;
  let testDataset: Dataset;
  let createTracesSpy: MockInstance<typeof opikClient.api.traces.createTraces>;
  let getDatasetByIdentifierSpy: MockInstance<
    typeof opikClient.api.datasets.getDatasetByIdentifier
  >;
  let createSpansSpy: MockInstance<typeof opikClient.api.spans.createSpans>;
  let createExperimentSpy: MockInstance<
    typeof opikClient.api.experiments.createExperiment
  >;
  let streamDatasetItemsSpy: MockInstance<
    typeof opikClient.api.datasets.streamDatasetItems
  >;
  let scoreBatchOfTracesSpy: MockInstance<
    typeof opikClient.api.traces.scoreBatchOfTraces
  >;

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    testDataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "test-dataset",
        description: "Test dataset for evaluation",
      },
      opikClient
    );

    createTracesSpy = vi
      .spyOn(opikClient.api.traces, "createTraces")
      .mockImplementation(mockAPIFunction);

    createSpansSpy = vi
      .spyOn(opikClient.api.spans, "createSpans")
      .mockImplementation(mockAPIFunction);

    scoreBatchOfTracesSpy = vi
      .spyOn(opikClient.api.traces, "scoreBatchOfTraces")
      .mockImplementation(mockAPIFunction);

    getDatasetByIdentifierSpy = vi
      .spyOn(opikClient.api.datasets, "getDatasetByIdentifier")
      .mockImplementation(() =>
        createMockHttpResponsePromise({
          name: testDataset.name,
        })
      );

    createExperimentSpy = vi
      .spyOn(opikClient.api.experiments, "createExperiment")
      .mockImplementation(mockAPIFunction);
    streamDatasetItemsSpy = vi
      .spyOn(opikClient.api.datasets, "streamDatasetItems")
      .mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            dataset_item_id: "019c6c79-9e01-760b-aca7-2d8013169301",
            data: { input: "test input 1", expected: "test output 1" },
            source: "sdk",
          }) + "\n"
        )
      );

    // Mock listDatasetVersions for getVersionInfo() calls
    vi.spyOn(opikClient.api.datasets, "listDatasetVersions").mockImplementation(
      () =>
        createMockHttpResponsePromise({
          content: [{ id: "version-1", versionName: "v1" }],
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

  test("should execute evaluation successfully", async () => {
    const mockDatasetItemId = "019c6c79-9e01-760b-aca7-2d8013169301";
    const mockTask: EvaluationTask = async () => {
      return { output: "generated output" };
    };

    const result = await evaluate({
      dataset: testDataset,
      task: mockTask,
      experimentName: "test-experiment",
      client: opikClient,
    });

    expect(createExperimentSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "test-experiment",
        datasetName: "test-dataset",
      })
    );

    expect(streamDatasetItemsSpy).toHaveBeenCalled();
    expect(getDatasetByIdentifierSpy).toHaveBeenCalled();

    expect(createTracesSpy).toHaveBeenCalled();
    const createTracesCall = createTracesSpy.mock.calls[0][0];
    expect(createTracesCall).toEqual(
      expect.objectContaining({
        traces: expect.arrayContaining([
          expect.objectContaining({
            createdBy: "evaluation",
            endTime: expect.any(Date),
            id: expect.any(String),
            input: {
              expected: "test output 1",
              id: mockDatasetItemId,
              input: "test input 1",
            },
            name: "evaluation_task",
            output: {
              output: "generated output",
            },
            projectName: "opik-sdk-typescript-test",
            startTime: expect.any(Date),
          }),
        ]),
      })
    );

    expect(createSpansSpy).toHaveBeenCalled();
    const createSpansCall = createSpansSpy.mock.calls[0][0];
    expect(createSpansCall).toEqual(
      expect.objectContaining({
        spans: expect.arrayContaining([
          expect.objectContaining({
            type: SpanType.General,
            name: "llm_task",
          }),
        ]),
      })
    );

    expect(result).toEqual({
      experimentId: expect.any(String),
      experimentName: "test-experiment",
      testResults: [
        expect.objectContaining({
          testCase: expect.objectContaining({
            datasetItemId: mockDatasetItemId,
            traceId: expect.any(String),
            taskOutput: { output: "generated output" },
            scoringInputs: expect.objectContaining({
              input: "test input 1",
              expected: "test output 1",
              output: "generated output",
            }),
          }),
          scoreResults: [],
        }),
      ],
    });
  });

  test("should execute evaluation with scoring metrics", async () => {
    const mockTask: EvaluationTask = async () => {
      return { output: "generated output" };
    };
    const result = await evaluate({
      dataset: testDataset,
      task: mockTask,
      experimentName: "test-experiment",
      scoringMetrics: [new ExactMatch("test-metric")],
      client: opikClient,
    });

    expect(scoreBatchOfTracesSpy).toHaveBeenCalled();
    const scoreBatchCall = scoreBatchOfTracesSpy.mock.calls[0][0];

    expect(scoreBatchCall).toEqual(
      expect.objectContaining({
        scores: expect.arrayContaining([
          expect.objectContaining({
            name: "test-metric",
            value: 0,
            reason: expect.stringContaining("Exact match: No match"),
            projectName: "opik-sdk-typescript-test",
          }),
        ]),
      })
    );

    expect(result.testResults[0].scoreResults).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          name: "test-metric",
          value: 0,
          reason: expect.stringContaining("Exact match: No match"),
        }),
      ])
    );

    expect(createSpansSpy).toHaveBeenCalled();
    const metricsSpanCall = createSpansSpy.mock.calls.find((call) => {
      const spans = call[0]?.spans || [];
      return spans.some((span) => span.name === "metrics_calculation");
    })?.[0];

    expect(metricsSpanCall).toBeDefined();
    expect(metricsSpanCall).toEqual(
      expect.objectContaining({
        spans: expect.arrayContaining([
          expect.objectContaining({
            type: SpanType.General,
            name: "metrics_calculation",
          }),
        ]),
      })
    );
  });

  test("should throw error when dataset is missing", async () => {
    const mockTask: EvaluationTask = async () => {
      return { output: "generated output" };
    };

    await expect(
      // @ts-expect-error - Intentionally missing required property
      evaluate({
        task: mockTask,
        experimentName: "test-experiment",
        client: opikClient,
      })
    ).rejects.toThrow("Dataset is required for evaluation");
    await expect(
      // @ts-expect-error - Intentionally missing required property
      evaluate({
        task: mockTask,
        experimentName: "test-experiment",
        client: opikClient,
      })
    ).rejects.toThrow("Dataset is required for evaluation");

    expect(createExperimentSpy).not.toHaveBeenCalled();
  });

  test("should throw error when task is missing", async () => {
    await expect(
      // @ts-expect-error - Intentionally missing required property
      evaluate({
        dataset: testDataset,
        experimentName: "test-experiment",
        client: opikClient,
      })
    ).rejects.toThrow("Task function is required for evaluation");

    expect(createExperimentSpy).not.toHaveBeenCalled();
  });
});
