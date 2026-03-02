import { describe, test, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import { z } from "zod";

vi.mock("@/evaluation/suite_evaluators/LLMJudge", () => {
  const mockScore = vi.fn().mockResolvedValue([
    { name: "Response is helpful", value: 1, reason: "Pass" },
  ]);

  class MockLLMJudge {
    name: string;
    trackMetric = false;
    validationSchema = z.object({}).passthrough();
    score = mockScore;

    constructor(opts: { name?: string }) {
      this.name = opts?.name ?? "llm_judge";
    }

    toConfig() {
      return {};
    }

    static fromConfig = vi.fn().mockImplementation(() => new MockLLMJudge({}));
  }

  return { LLMJudge: MockLLMJudge };
});

import { evaluateSuite } from "@/evaluation/suite/evaluateSuite";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { EvaluationTask } from "@/evaluation/types";
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import {
  createMockHttpResponsePromise,
  mockAPIFunction,
  mockAPIFunctionWithStream,
} from "../../../mockUtils";

describe("evaluateSuite", () => {
  let opikClient: OpikClient;
  let testDataset: Dataset;
  let createTracesSpy: MockInstance<typeof opikClient.api.traces.createTraces>;
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
  let listDatasetVersionsSpy: MockInstance<
    typeof opikClient.api.datasets.listDatasetVersions
  >;

  const mockTask: EvaluationTask = async () => {
    return { output: "generated output" };
  };

  beforeEach(() => {
    vi.clearAllMocks();

    // Reset LLMJudge.fromConfig mock to return a fresh instance each time
    (LLMJudge.fromConfig as ReturnType<typeof vi.fn>).mockImplementation(
      () => {
        const instance = new (LLMJudge as unknown as new (opts: Record<string, unknown>) => InstanceType<typeof LLMJudge>)({});
        instance.score = vi.fn().mockResolvedValue([
          { name: "Response is helpful", value: 1, reason: "Pass" },
        ]);
        return instance;
      }
    );

    opikClient = new OpikClient({
      projectName: "opik-sdk-typescript-test",
    });

    testDataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "test-dataset",
        description: "Test dataset for evaluation suite",
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

    vi.spyOn(opikClient.api.datasets, "getDatasetByIdentifier").mockImplementation(
      () =>
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
            id: "item-1",
            data: { input: "test input 1", expected: "test output 1" },
            source: "sdk",
          }) + "\n"
        )
      );

    listDatasetVersionsSpy = vi
      .spyOn(opikClient.api.datasets, "listDatasetVersions")
      .mockImplementation(() =>
        createMockHttpResponsePromise({
          content: [
            {
              id: "version-1",
              versionName: "v1",
              evaluators: [
                {
                  name: "llm_judge",
                  type: "llm_judge",
                  config: {
                    version: "1.0.0",
                    name: "llm_judge",
                    model: { name: "gpt-5-nano" },
                    messages: [
                      { role: "SYSTEM", content: "system prompt" },
                      { role: "USER", content: "user prompt" },
                    ],
                    variables: { input: "string", output: "string" },
                    schema: [
                      {
                        name: "Response is helpful",
                        type: "BOOLEAN",
                        description: "Response is helpful",
                      },
                    ],
                  },
                },
              ],
              executionPolicy: { runsPerItem: 1, passThreshold: 1 },
            },
          ],
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

  test("creates experiment for suite evaluation with correct parameters", async () => {
    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    expect(createExperimentSpy).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "suite-experiment",
        datasetName: "test-dataset",
        datasetVersionId: "version-1",
        // TODO: assert evaluationMethod: "evaluation_suite" once Fern-generated ExperimentWrite includes the field
      })
    );

    expect(result.experimentId).toBeDefined();
    expect(result.experimentName).toBe("suite-experiment");
  });

  test("reads evaluators from dataset version metadata and uses them for scoring", async () => {
    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // Verify LLMJudge.fromConfig was called to deserialize the evaluator
    expect(LLMJudge.fromConfig).toHaveBeenCalled();

    // Verify listDatasetVersions was called to fetch version info
    expect(listDatasetVersionsSpy).toHaveBeenCalled();

    // Verify score results are present in the output
    expect(result.testResults).toHaveLength(1);
    expect(result.testResults[0].scoreResults).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          name: "Response is helpful",
          value: 1,
          reason: "Pass",
        }),
      ])
    );
  });

  test("reads execution policy from dataset version", async () => {
    // Override with runsPerItem: 2
    listDatasetVersionsSpy.mockImplementation(() =>
      createMockHttpResponsePromise({
        content: [
          {
            id: "version-1",
            versionName: "v1",
            evaluators: [
              {
                name: "llm_judge",
                type: "llm_judge",
                config: {
                  version: "1.0.0",
                  name: "llm_judge",
                  model: { name: "gpt-5-nano" },
                  messages: [
                    { role: "SYSTEM", content: "system prompt" },
                    { role: "USER", content: "user prompt" },
                  ],
                  variables: { input: "string", output: "string" },
                  schema: [
                    {
                      name: "Response is helpful",
                      type: "BOOLEAN",
                      description: "Response is helpful",
                    },
                  ],
                },
              },
            ],
            executionPolicy: { runsPerItem: 2, passThreshold: 1 },
          },
        ],
        page: 1,
        size: 1,
        total: 1,
      })
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // Verify listDatasetVersions was called to read version info
    expect(listDatasetVersionsSpy).toHaveBeenCalled();

    // runsPerItem: 2 means 1 item * 2 runs = 2 test results
    expect(result.testResults).toHaveLength(2);
  });

  test("executes task once per item when runsPerItem=1", async () => {
    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // 1 item * 1 run = 1 test result
    expect(result.testResults).toHaveLength(1);

    // 1 trace created total (batched)
    const allTraces = createTracesSpy.mock.calls.flatMap(
      (call) => call[0]?.traces ?? []
    );
    expect(allTraces).toHaveLength(1);
  });

  test("executes task N times per item when runsPerItem=N, assigns trialId 0..N-1", async () => {
    // Override with runsPerItem: 3
    listDatasetVersionsSpy.mockImplementation(() =>
      createMockHttpResponsePromise({
        content: [
          {
            id: "version-1",
            versionName: "v1",
            evaluators: [
              {
                name: "llm_judge",
                type: "llm_judge",
                config: {
                  version: "1.0.0",
                  name: "llm_judge",
                  model: { name: "gpt-5-nano" },
                  messages: [
                    { role: "SYSTEM", content: "system prompt" },
                    { role: "USER", content: "user prompt" },
                  ],
                  variables: { input: "string", output: "string" },
                  schema: [
                    {
                      name: "Response is helpful",
                      type: "BOOLEAN",
                      description: "Response is helpful",
                    },
                  ],
                },
              },
            ],
            executionPolicy: { runsPerItem: 3, passThreshold: 2 },
          },
        ],
        page: 1,
        size: 1,
        total: 1,
      })
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // 1 item * 3 runs = 3 test results
    expect(result.testResults).toHaveLength(3);

    // Verify trialIds are 0, 1, 2
    const trialIds = result.testResults.map((r) => r.trialId);
    expect(trialIds).toEqual([0, 1, 2]);

    // All results should reference the same dataset item
    const datasetItemIds = result.testResults.map(
      (r) => r.testCase.datasetItemId
    );
    expect(datasetItemIds).toEqual(["item-1", "item-1", "item-1"]);

    // Traces are batched - verify the total number of traces across all calls
    const allTraces = createTracesSpy.mock.calls.flatMap(
      (call) => call[0]?.traces ?? []
    );
    expect(allTraces).toHaveLength(3);

    // Each trace should have a unique traceId
    const traceIds = result.testResults.map((r) => r.testCase.traceId);
    const uniqueTraceIds = new Set(traceIds);
    expect(uniqueTraceIds.size).toBe(3);
  });

  test("returns EvaluationResult with correct structure including trialId", async () => {
    // Override with runsPerItem: 2
    listDatasetVersionsSpy.mockImplementation(() =>
      createMockHttpResponsePromise({
        content: [
          {
            id: "version-1",
            versionName: "v1",
            evaluators: [
              {
                name: "llm_judge",
                type: "llm_judge",
                config: {
                  version: "1.0.0",
                  name: "llm_judge",
                  model: { name: "gpt-5-nano" },
                  messages: [
                    { role: "SYSTEM", content: "system prompt" },
                    { role: "USER", content: "user prompt" },
                  ],
                  variables: { input: "string", output: "string" },
                  schema: [
                    {
                      name: "Response is helpful",
                      type: "BOOLEAN",
                      description: "Response is helpful",
                    },
                  ],
                },
              },
            ],
            executionPolicy: { runsPerItem: 2, passThreshold: 1 },
          },
        ],
        page: 1,
        size: 1,
        total: 1,
      })
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    expect(result).toEqual({
      experimentId: expect.any(String),
      experimentName: "suite-experiment",
      testResults: [
        expect.objectContaining({
          testCase: expect.objectContaining({
            datasetItemId: "item-1",
            traceId: expect.any(String),
            taskOutput: { output: "generated output" },
          }),
          scoreResults: expect.arrayContaining([
            expect.objectContaining({
              name: "Response is helpful",
              value: 1,
            }),
          ]),
          trialId: 0,
        }),
        expect.objectContaining({
          testCase: expect.objectContaining({
            datasetItemId: "item-1",
            traceId: expect.any(String),
            taskOutput: { output: "generated output" },
          }),
          scoreResults: expect.arrayContaining([
            expect.objectContaining({
              name: "Response is helpful",
              value: 1,
            }),
          ]),
          trialId: 1,
        }),
      ],
    });
  });

  test("throws error when dataset is missing", async () => {
    await expect(
      // @ts-expect-error - Intentionally missing required property
      evaluateSuite({
        task: mockTask,
        experimentName: "suite-experiment",
        client: opikClient,
      })
    ).rejects.toThrow("Dataset is required for evaluation suite");

    expect(createExperimentSpy).not.toHaveBeenCalled();
  });

  test("throws error when task is missing", async () => {
    await expect(
      // @ts-expect-error - Intentionally missing required property
      evaluateSuite({
        dataset: testDataset,
        experimentName: "suite-experiment",
        client: opikClient,
      })
    ).rejects.toThrow("Task function is required for evaluation suite");

    expect(createExperimentSpy).not.toHaveBeenCalled();
  });

  test("passes evaluatorModel to deserializeEvaluators via LLMJudge.fromConfig", async () => {
    await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      evaluatorModel: "gpt-5-mini",
      client: opikClient,
    });

    expect(LLMJudge.fromConfig).toHaveBeenCalledWith(
      expect.any(Object),
      { model: "gpt-5-mini" }
    );
  });

  test("handles multiple dataset items with runsPerItem > 1", async () => {
    // Stream 2 dataset items
    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(
        JSON.stringify({
          id: "item-1",
          data: { input: "test input 1", expected: "test output 1" },
          source: "sdk",
        }) +
          "\n" +
          JSON.stringify({
            id: "item-2",
            data: { input: "test input 2", expected: "test output 2" },
            source: "sdk",
          }) +
          "\n"
      )
    );

    // Override with runsPerItem: 2
    listDatasetVersionsSpy.mockImplementation(() =>
      createMockHttpResponsePromise({
        content: [
          {
            id: "version-1",
            versionName: "v1",
            evaluators: [
              {
                name: "llm_judge",
                type: "llm_judge",
                config: {
                  version: "1.0.0",
                  name: "llm_judge",
                  model: { name: "gpt-5-nano" },
                  messages: [
                    { role: "SYSTEM", content: "system prompt" },
                    { role: "USER", content: "user prompt" },
                  ],
                  variables: { input: "string", output: "string" },
                  schema: [
                    {
                      name: "Response is helpful",
                      type: "BOOLEAN",
                      description: "Response is helpful",
                    },
                  ],
                },
              },
            ],
            executionPolicy: { runsPerItem: 2, passThreshold: 1 },
          },
        ],
        page: 1,
        size: 1,
        total: 1,
      })
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // 2 items * 2 runs = 4 test results
    expect(result.testResults).toHaveLength(4);

    // Verify trialIds alternate for each item
    const item1Results = result.testResults.filter(
      (r) => r.testCase.datasetItemId === "item-1"
    );
    const item2Results = result.testResults.filter(
      (r) => r.testCase.datasetItemId === "item-2"
    );

    expect(item1Results).toHaveLength(2);
    expect(item1Results.map((r) => r.trialId)).toEqual([0, 1]);
    expect(item2Results).toHaveLength(2);
    expect(item2Results.map((r) => r.trialId)).toEqual([0, 1]);

    // 4 traces total (batched) - verify the total number across all calls
    const allTraces = createTracesSpy.mock.calls.flatMap(
      (call) => call[0]?.traces ?? []
    );
    expect(allTraces).toHaveLength(4);
  });

  test("streamDatasetItems is called only once (prefetched items, no duplicate fetch)", async () => {
    await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // getRawItems() calls streamDatasetItems once; engine uses prefetchedItems
    expect(streamDatasetItemsSpy).toHaveBeenCalledTimes(1);
  });

  test("item with evaluators — merged with suite-level evaluators", async () => {
    // Item has its own evaluator stored in data
    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(
        JSON.stringify({
          id: "item-1",
          data: { input: "test input 1", expected: "test output 1" },
          source: "sdk",
          evaluators: [
            {
              name: "item-judge",
              type: "llm_judge",
              config: {
                name: "item-judge",
                schema: [{ name: "item-specific", type: "BOOLEAN" }],
                model: { name: "gpt-5-nano" },
              },
            },
          ],
        }) + "\n"
      )
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // LLMJudge.fromConfig called: 1 for suite-level + 1 for item-level = 2
    expect(LLMJudge.fromConfig).toHaveBeenCalledTimes(2);

    // Each run should produce scores from both suite + item evaluators
    expect(result.testResults).toHaveLength(1);
    // 2 evaluators × 1 score each = 2 score results
    expect(result.testResults[0].scoreResults).toHaveLength(2);
  });

  test("item without evaluators — only suite-level metrics used", async () => {
    // Item without evaluators field
    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(
        JSON.stringify({
          id: "item-1",
          data: { input: "test input 1", expected: "test output 1" },
          source: "sdk",
        }) + "\n"
      )
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // LLMJudge.fromConfig called: 1 for suite-level only
    expect(LLMJudge.fromConfig).toHaveBeenCalledTimes(1);
    expect(result.testResults).toHaveLength(1);
    expect(result.testResults[0].scoreResults).toHaveLength(1);
  });

  test("per-item executionPolicy — different runsPerItem per item", async () => {
    // Two items: item-1 has runsPerItem=3, item-2 has no override (falls back to suite-level 1)
    // Note: Fern serializer expects snake_case in raw JSON
    streamDatasetItemsSpy.mockImplementation(() =>
      mockAPIFunctionWithStream(
        JSON.stringify({
          id: "item-1",
          data: { input: "test input 1" },
          source: "sdk",
          execution_policy: { runs_per_item: 3 },
        }) +
          "\n" +
          JSON.stringify({
            id: "item-2",
            data: { input: "test input 2" },
            source: "sdk",
          }) +
          "\n"
      )
    );

    const result = await evaluateSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    // item-1: 3 runs, item-2: 1 run = 4 total
    expect(result.testResults).toHaveLength(4);

    const item1Results = result.testResults.filter(
      (r) => r.testCase.datasetItemId === "item-1"
    );
    const item2Results = result.testResults.filter(
      (r) => r.testCase.datasetItemId === "item-2"
    );

    expect(item1Results).toHaveLength(3);
    expect(item1Results.map((r) => r.trialId)).toEqual([0, 1, 2]);
    expect(item2Results).toHaveLength(1);
    expect(item2Results.map((r) => r.trialId)).toEqual([0]);
  });

});
