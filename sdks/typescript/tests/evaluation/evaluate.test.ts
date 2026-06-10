import { describe, test, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { evaluate } from "@/evaluation/evaluate";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { EvaluationTask, TASK_ERROR_SCORE_NAME } from "@/evaluation/types";
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
  let createExperimentItemsSpy: MockInstance<
    typeof opikClient.api.experiments.createExperimentItems
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
      opikClient,
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
        }),
      );

    createExperimentSpy = vi
      .spyOn(opikClient.api.experiments, "createExperiment")
      .mockImplementation(mockAPIFunction);
    streamDatasetItemsSpy = vi
      .spyOn(opikClient.api.datasets, "streamDatasetItems")
      .mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            id: "item-1",
            data: { input: "test input 1", expected: "test output 1" },
            source: "sdk",
          }) + "\n",
        ),
      );

    // Mock listDatasetVersions for getVersionInfo() calls
    vi.spyOn(opikClient.api.datasets, "listDatasetVersions").mockImplementation(
      () =>
        createMockHttpResponsePromise({
          content: [{ id: "version-1", versionName: "v1" }],
          page: 1,
          size: 1,
          total: 1,
        }),
    );

    createExperimentItemsSpy = vi
      .spyOn(opikClient.api.experiments, "createExperimentItems")
      .mockImplementation(mockAPIFunction);

    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("should execute evaluation successfully", async () => {
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
      }),
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
              id: "item-1",
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
      }),
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
      }),
    );

    expect(result).toEqual({
      experimentId: expect.any(String),
      experimentName: "test-experiment",
      resultUrl: expect.any(String),
      errors: [],
      testResults: [
        expect.objectContaining({
          testCase: expect.objectContaining({
            datasetItemId: "item-1",
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

  test("should return failed test results when task execution fails", async () => {
    const mockTask: EvaluationTask = async () => {
      throw new Error("task failed");
    };

    const result = await evaluate({
      dataset: testDataset,
      task: mockTask,
      experimentName: "test-experiment",
      client: opikClient,
    });

    expect(result.errors).toEqual([
      expect.objectContaining({
        datasetItemId: "item-1",
        runIndex: 0,
        message: "task failed",
        error: expect.any(Error),
      }),
    ]);
    expect(result.testResults).toHaveLength(1);
    expect(result.testResults[0]).toEqual(
      expect.objectContaining({
        testCase: expect.objectContaining({
          datasetItemId: "item-1",
          traceId: expect.any(String),
          taskOutput: {},
          scoringInputs: expect.objectContaining({
            input: "test input 1",
            expected: "test output 1",
          }),
        }),
        scoreResults: [
          expect.objectContaining({
            name: TASK_ERROR_SCORE_NAME,
            value: 0,
            reason: "task failed",
            scoringFailed: true,
          }),
        ],
      }),
    );

    const createTracesCall = createTracesSpy.mock.calls[0][0];
    const capturedTraceId = createTracesCall.traces[0].id;
    expect(capturedTraceId).toBeDefined();
    expect(result.testResults[0].testCase.traceId).toBe(capturedTraceId);
    expect(createTracesCall).toEqual(
      expect.objectContaining({
        traces: expect.arrayContaining([
          expect.objectContaining({
            errorInfo: expect.objectContaining({
              message: "task failed",
              exceptionType: "Error",
            }),
          }),
        ]),
      }),
    );

    // Failed run must still be attached to the experiment so it appears in the UI.
    expect(createExperimentItemsSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        experimentItems: expect.arrayContaining([
          expect.objectContaining({
            datasetItemId: "item-1",
            traceId: capturedTraceId,
          }),
        ]),
      }),
    );
  });

  test("surfaces original task error when experiment insert also fails", async () => {
    const mockTask: EvaluationTask = async () => {
      throw new Error("task failed");
    };

    createExperimentItemsSpy.mockRejectedValueOnce(new Error("insert failed"));

    const result = await evaluate({
      dataset: testDataset,
      task: mockTask,
      experimentName: "test-experiment",
      client: opikClient,
    });

    expect(result.errors).toHaveLength(1);
    expect(result.errors[0].message).toBe("task failed");
    expect(result.errors[0].error?.message).toBe("task failed");
    expect(result.testResults).toHaveLength(1);
    expect(result.testResults[0].scoreResults[0].name).toBe(
      TASK_ERROR_SCORE_NAME,
    );
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
      }),
    );

    expect(result.testResults[0].scoreResults).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          name: "test-metric",
          value: 0,
          reason: expect.stringContaining("Exact match: No match"),
        }),
      ]),
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
      }),
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
      }),
    ).rejects.toThrow("Dataset is required for evaluation");
    await expect(
      // @ts-expect-error - Intentionally missing required property
      evaluate({
        task: mockTask,
        experimentName: "test-experiment",
        client: opikClient,
      }),
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
      }),
    ).rejects.toThrow("Task function is required for evaluation");

    expect(createExperimentSpy).not.toHaveBeenCalled();
  });

  test("should include blueprint metadata when blueprintId is provided", async () => {
    const getBlueprintSpy = vi
      .spyOn(opikClient.api.agentConfigs, "getBlueprintById")
      .mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "bp-123",
          name: "v9",
          type: "blueprint",
          values: [],
        }),
      );

    const mockTask: EvaluationTask = async () => {
      return { output: "result" };
    };

    await evaluate({
      dataset: testDataset,
      task: mockTask,
      experimentName: "blueprint-test",
      client: opikClient,
      blueprintId: "bp-123",
    });

    expect(getBlueprintSpy).toHaveBeenCalledWith("bp-123");
    expect(createExperimentSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.objectContaining({
          agent_configuration: {
            _blueprint_id: "bp-123",
            blueprint_version: "v9",
          },
        }),
      }),
    );
  });

  test("should store blueprint_id even when fetch fails", async () => {
    vi.spyOn(
      opikClient.api.agentConfigs,
      "getBlueprintById",
    ).mockImplementation(() => {
      throw new Error("not found");
    });

    const mockTask: EvaluationTask = async () => {
      return { output: "result" };
    };

    await evaluate({
      dataset: testDataset,
      task: mockTask,
      experimentName: "blueprint-fail-test",
      client: opikClient,
      blueprintId: "bp-456",
    });

    expect(createExperimentSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.objectContaining({
          agent_configuration: {
            _blueprint_id: "bp-456",
          },
        }),
      }),
    );
  });
});
