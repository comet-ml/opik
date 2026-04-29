import { describe, test, expect, vi, beforeEach, afterEach } from "vitest";
import { z } from "zod";

const { oraMock, getRecordedTexts, resetRecordedTexts } = vi.hoisted(() => {
  const texts: string[] = [];
  const spinner = {
    _text: "",
    get text() {
      return this._text;
    },
    set text(value: string) {
      this._text = value;
      texts.push(value);
    },
    start() {
      return this;
    },
    succeed: (_message?: string) => {},
    warn: (_message?: string) => {},
  };
  const oraMock = vi.fn((opts?: { text?: string } | string) => {
    const initialText =
      typeof opts === "string" ? opts : opts?.text ?? "";
    spinner._text = initialText;
    texts.push(initialText);
    return spinner;
  });
  return {
    oraMock,
    getRecordedTexts: () => [...texts],
    resetRecordedTexts: () => {
      texts.length = 0;
    },
  };
});

vi.mock("ora", () => ({ default: oraMock }));

vi.mock("@/evaluation/suite_evaluators/LLMJudge", () => {
  class MockLLMJudge {
    name: string;
    trackMetric = false;
    validationSchema = z.object({}).passthrough();
    score = vi
      .fn()
      .mockResolvedValue([
        { name: "Response is helpful", value: 1, reason: "Pass" },
      ]);

    constructor(opts: { name?: string }) {
      this.name = opts?.name ?? "llm_judge";
    }

    toConfig() {
      return {};
    }

    static fromConfig = vi
      .fn()
      .mockImplementation(() => new MockLLMJudge({}));

    static merged(judges: MockLLMJudge[]): MockLLMJudge | undefined {
      if (judges.length <= 1) return undefined;
      return undefined;
    }
  }

  return { LLMJudge: MockLLMJudge };
});

import { evaluate } from "@/evaluation/evaluate";
import { evaluateTestSuite } from "@/evaluation/suite/evaluateTestSuite";
import { OpikClient } from "@/client/Client";
import { Dataset } from "@/dataset/Dataset";
import { EvaluationTask } from "@/evaluation/types";
import {
  createMockHttpResponsePromise,
  mockAPIFunction,
  mockAPIFunctionWithStream,
} from "@tests/mockUtils";

describe("EvaluationEngine progress spinner label", () => {
  let opikClient: OpikClient;
  let testDataset: Dataset;

  const mockTask: EvaluationTask = async () => ({ output: "generated output" });

  beforeEach(() => {
    vi.clearAllMocks();
    resetRecordedTexts();
    vi.useFakeTimers();

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
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  test("evaluateTestSuite: initial spinner label reads 'Evaluating test suite'", async () => {
    await evaluateTestSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    expect(oraMock).toHaveBeenCalled();
    const initialArg = oraMock.mock.calls[0][0] as { text?: string };
    const initialText =
      typeof initialArg === "string" ? initialArg : initialArg?.text ?? "";

    expect(initialText).toContain("Evaluating test suite");
    expect(initialText).not.toContain("Evaluating dataset");
  });

  test("evaluateTestSuite: update() callback keeps 'Evaluating test suite'", async () => {
    await evaluateTestSuite({
      dataset: testDataset,
      task: mockTask,
      experimentName: "suite-experiment",
      client: opikClient,
    });

    const texts = getRecordedTexts();

    // Only the update() branch emits percentage-formatted labels.
    // Asserting on these specifically guards the update() call site,
    // independent of the initial spinner label already covered above.
    const updateTexts = texts.filter((t) => /\d+%/.test(t));
    expect(updateTexts.length).toBeGreaterThan(0);

    for (const text of updateTexts) {
      expect(text).toContain("Evaluating test suite");
      expect(text).not.toContain("Evaluating dataset");
    }
  });

  test("evaluate (non-suite): spinner label reads 'Evaluating dataset'", async () => {
    await evaluate({
      dataset: testDataset,
      task: mockTask,
      scoringMetrics: [],
      experimentName: "test-experiment",
      client: opikClient,
    });

    expect(oraMock).toHaveBeenCalled();
    const initialArg = oraMock.mock.calls[0][0] as { text?: string };
    const initialText =
      typeof initialArg === "string" ? initialArg : initialArg?.text ?? "";

    expect(initialText).toContain("Evaluating dataset");
    expect(initialText).not.toContain("Evaluating test suite");
  });
});
