import { stripVTControlCharacters } from "node:util";
import { describe, test, expect, vi, afterEach } from "vitest";
import { EvaluationResultProcessor } from "@/evaluation/results";
import {
  EvaluationTestResult,
  TASK_ERROR_SCORE_NAME,
} from "@/evaluation/types";
import { Experiment } from "@/experiment/Experiment";
import { logger } from "@/utils/logger";

const fakeExperiment = {
  id: "experiment-id",
  getUrl: async () => "http://localhost/experiment",
  ensureNameLoaded: async () => "my-experiment",
} as unknown as Experiment;

function testResult(
  scoreResults: EvaluationTestResult["scoreResults"],
): EvaluationTestResult {
  return {
    testCase: {
      traceId: "trace-id",
      datasetItemId: "item-id",
      scoringInputs: {},
      taskOutput: {},
    },
    scoreResults,
  };
}

async function summaryLines(testResults: EvaluationTestResult[]) {
  const infoSpy = vi.spyOn(logger, "info").mockImplementation(() => undefined);
  await EvaluationResultProcessor.processResults(testResults, fakeExperiment);
  const output = stripVTControlCharacters(
    infoSpy.mock.calls.map((call) => String(call[0])).join("\n"),
  );
  return output.split("\n").map((line) => line.replace(/[│╭╮╰╯─]/g, "").trim());
}

describe("EvaluationResultProcessor summary", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  test("shows how many scores failed next to the average", async () => {
    const lines = await summaryLines([
      testResult([{ name: "hallucination", value: 1 }]),
      testResult([{ name: "hallucination", value: 0.5 }]),
      testResult([
        {
          name: "hallucination",
          value: 0,
          reason: "boom",
          scoringFailed: true,
        },
      ]),
    ]);

    expect(lines).toContain("hallucination: 0.7500 (avg) - 1 failed");
  });

  test("shows None when every score of a metric failed", async () => {
    const lines = await summaryLines([
      testResult([
        { name: "judge", value: 0, reason: "boom", scoringFailed: true },
      ]),
      testResult([
        { name: "judge", value: 0, reason: "boom", scoringFailed: true },
      ]),
    ]);

    expect(lines).toContain("judge: None (avg) - 2 failed");
  });

  test("leaves task failures out of the metric lines", async () => {
    const lines = await summaryLines([
      testResult([{ name: "exact_match", value: 1 }]),
      testResult([
        {
          name: TASK_ERROR_SCORE_NAME,
          value: 0,
          reason: "task failed",
          scoringFailed: true,
        },
      ]),
    ]);

    expect(lines).toContain("exact_match: 1.0000 (avg)");
    expect(lines.join("\n")).not.toContain(TASK_ERROR_SCORE_NAME);
  });

  test("keeps the plain average when nothing failed", async () => {
    const lines = await summaryLines([
      testResult([{ name: "exact_match", value: 1 }]),
      testResult([{ name: "exact_match", value: 0 }]),
    ]);

    expect(lines).toContain("exact_match: 0.5000 (avg)");
  });
});
