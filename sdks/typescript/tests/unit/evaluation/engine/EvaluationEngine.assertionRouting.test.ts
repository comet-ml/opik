import { describe, test, expect, vi, beforeEach, afterEach } from "vitest";
import { z } from "zod";

vi.mock("ora", () => ({
  default: vi.fn(() => ({
    start() {
      return this;
    },
    text: "",
    succeed: () => {},
    warn: () => {},
  })),
}));

vi.mock("@/evaluation/suite_evaluators/LLMJudge", async () => {
  const { BaseSuiteEvaluator } = await import(
    "@/evaluation/suite_evaluators/BaseSuiteEvaluator"
  );

  class MockLLMJudge extends BaseSuiteEvaluator {
    score = vi.fn().mockResolvedValue([
      { name: "Response is helpful", value: 1, reason: "Pass" },
      { name: "No hallucinations", value: 0, reason: "Hallucinated" },
    ]);

    constructor(opts: { name?: string } = {}) {
      super(opts?.name ?? "llm_judge", false);
    }

    toConfig() {
      return {} as never;
    }

    static fromConfig = vi
      .fn()
      .mockImplementation(() => new MockLLMJudge({}));

    static merged(): undefined {
      return undefined;
    }
  }

  return { LLMJudge: MockLLMJudge };
});

import { evaluate } from "@/evaluation/evaluate";
import { evaluateTestSuite } from "@/evaluation/suite/evaluateTestSuite";
import { buildSuiteResult } from "@/evaluation/suite/suiteResultConstructor";
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { BaseMetric } from "@/evaluation/metrics/BaseMetric";
import { EvaluationTask, EvaluationScoreResult } from "@/evaluation/types";
import {
  createMockHttpResponsePromise,
  mockAPIFunction,
  mockAPIFunctionWithStream,
} from "@tests/mockUtils";

class MockHeuristic extends BaseMetric {
  public readonly validationSchema = z.object({}).passthrough();

  constructor() {
    super("contains_word", false);
  }

  score(): EvaluationScoreResult {
    return { name: "contains_word", value: 0.75, reason: "Found it" };
  }
}

describe("EvaluationEngine routes scores to the right batch queue", () => {
  let opikClient: OpikClient;
  let testDataset: Dataset;

  const mockTask: EvaluationTask = async () => ({ output: "generated output" });

  const getStoreAssertionsBatchSpy = () =>
    vi.mocked(opikClient.api.assertionResults.storeAssertionsBatch);
  const getScoreBatchOfTracesSpy = () =>
    vi.mocked(opikClient.api.traces.scoreBatchOfTraces);

  beforeEach(() => {
    vi.clearAllMocks();

    opikClient = new OpikClient({ projectName: "opik-sdk-typescript-test" });

    testDataset = new Dataset(
      {
        id: "test-dataset-id",
        name: "test-dataset",
        description: "Test dataset",
      },
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
    vi.spyOn(
      opikClient.api.assertionResults,
      "storeAssertionsBatch"
    ).mockImplementation(mockAPIFunction);
    vi.spyOn(
      opikClient.api.datasets,
      "getDatasetByIdentifier"
    ).mockImplementation(() =>
      createMockHttpResponsePromise({ name: testDataset.name })
    );
    vi.spyOn(opikClient.api.experiments, "createExperiment").mockImplementation(
      mockAPIFunction
    );
    vi.spyOn(
      opikClient.api.experiments,
      "createExperimentItems"
    ).mockImplementation(mockAPIFunction);
    vi.spyOn(opikClient.api.datasets, "streamDatasetItems").mockImplementation(
      () =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            id: "item-1",
            data: { input: "test input 1", expected: "test output 1" },
            source: "sdk",
          }) + "\n"
        )
    );
    vi.spyOn(opikClient.api.datasets, "listDatasetVersions").mockImplementation(
      () =>
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
                      {
                        name: "No hallucinations",
                        type: "BOOLEAN",
                        description: "No hallucinations",
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
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("evaluateTestSuite: assertion results go to /v1/private/assertion-results, not feedback-scores", async () => {
    await evaluateTestSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    await opikClient.flush({ silent: true });

    const storeAssertionsBatch = getStoreAssertionsBatchSpy();
    expect(storeAssertionsBatch).toHaveBeenCalledTimes(1);
    const batchPayload = storeAssertionsBatch.mock.calls[0][0];

    expect(batchPayload.entityType).toBe("TRACE");
    expect(batchPayload.assertionResults).toHaveLength(2);
    expect(batchPayload.assertionResults[0]).toMatchObject({
      name: "Response is helpful",
      status: "passed",
      reason: "Pass",
      source: "sdk",
      projectName: "opik-sdk-typescript-test",
    });
    expect(batchPayload.assertionResults[1]).toMatchObject({
      name: "No hallucinations",
      status: "failed",
      reason: "Hallucinated",
      source: "sdk",
      projectName: "opik-sdk-typescript-test",
    });
    expect(batchPayload.assertionResults[0].entityId).toBeTruthy();

    expect(getScoreBatchOfTracesSpy()).not.toHaveBeenCalled();
  });

  test("evaluateTestSuite: an evaluator that throws fails the item and uploads no assertion result", async () => {
    // LLMJudge is the MockLLMJudge above; build one directly because
    // restoreAllMocks clears the fromConfig implementation between tests.
    const failingJudge = new (LLMJudge as unknown as new () => LLMJudge)();
    failingJudge.score = vi
      .fn()
      .mockRejectedValue(new Error("judge unavailable"));
    vi.mocked(LLMJudge.fromConfig).mockReturnValueOnce(failingJudge);

    const result = await evaluateTestSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    await opikClient.flush({ silent: true });

    expect(result.testResults[0].scoreResults).toEqual([
      {
        name: "llm_judge",
        value: 0,
        reason: "judge unavailable",
        scoringFailed: true,
      },
    ]);
    // With no score at all the run had no assertions and counted as passed.
    const suiteResult = buildSuiteResult(result);
    expect(suiteResult.itemResults.get("item-1")?.passed).toBe(false);
    expect(suiteResult.allItemsPassed).toBe(false);

    expect(getStoreAssertionsBatchSpy()).not.toHaveBeenCalled();
    expect(getScoreBatchOfTracesSpy()).not.toHaveBeenCalled();
  });

  test("evaluate (non-suite): regular metric scores still go to feedback-scores, not assertion-results", async () => {
    await evaluate({
      dataset: testDataset,
      task: mockTask,
      scoringMetrics: [new MockHeuristic()],
      experimentName: "regular-experiment",
      client: opikClient,
    });

    await opikClient.flush({ silent: true });

    const scoreBatchOfTraces = getScoreBatchOfTracesSpy();
    expect(scoreBatchOfTraces).toHaveBeenCalled();
    const { scores } = scoreBatchOfTraces.mock.calls[0][0];
    expect(scores).toHaveLength(1);
    expect(scores[0]).toMatchObject({ name: "contains_word", value: 0.75 });

    expect(getStoreAssertionsBatchSpy()).not.toHaveBeenCalled();
  });
});
