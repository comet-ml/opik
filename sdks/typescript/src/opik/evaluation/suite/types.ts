import type { ExecutionPolicyWrite } from "@/rest_api/api/types/ExecutionPolicyWrite";
import type { EvaluationTestResult, EvaluationScoreResult } from "../types";

/**
 * Execution policy for test suite items.
 * Mirrors the Fern-generated ExecutionPolicyWrite type.
 */
export type ExecutionPolicy = ExecutionPolicyWrite;

export const DEFAULT_EXECUTION_POLICY: Required<ExecutionPolicy> = {
  runsPerItem: 1,
  passThreshold: 1,
};

/**
 * A single item to be inserted into a test suite via `insert()`.
 */
export interface TestSuiteItem {
  data: Record<string, unknown>;
  assertions?: string[];
  description?: string;
  executionPolicy?: ExecutionPolicy;
}

/**
 * A single item to be updated in a test suite via `updateItems()`.
 * Requires an ID to identify the item to update.
 * All provided fields are merged with the existing item data - only the fields
 * you specify will be updated, preserving all other existing values.
 */
export interface UpdateTestSuiteItem {
  id: string;
  data?: Record<string, unknown>;
  assertions?: string[];
  description?: string;
  executionPolicy?: ExecutionPolicy;
}

/**
 * Result of an individual item in the test suite.
 */
export type ItemResult = {
  datasetItemId: string;
  passed: boolean;
  /** Whether this item had at least one assertion evaluated across any of its runs. */
  hasAssertions: boolean;
  runsPassed: number;
  runsTotal: number;
  /** Configured runsPerItem from the execution policy. */
  configuredRunsPerItem: number;
  passThreshold: number;
  testResults: EvaluationTestResult[];
};

function isScorePassed(score: EvaluationScoreResult): boolean {
  if (score.scoringFailed) return false;
  return score.value === 1;
}

/**
 * Result of a test suite run.
 *
 * Contains pass/fail status for each item based on execution policy,
 * as well as overall suite pass/fail status.
 */
export class TestSuiteResult {
  readonly allItemsPassed: boolean;
  readonly itemsPassed: number;
  readonly itemsTotal: number;
  readonly passRate: number | undefined;
  readonly itemResults: Map<string, ItemResult>;
  readonly experimentId: string;
  readonly experimentName?: string;
  readonly experimentUrl?: string;
  readonly suiteName?: string;
  readonly totalTime?: number;

  constructor(data: {
    allItemsPassed: boolean;
    itemsPassed: number;
    itemsTotal: number;
    passRate: number | undefined;
    itemResults: Map<string, ItemResult>;
    experimentId: string;
    experimentName?: string;
    experimentUrl?: string;
    suiteName?: string;
    totalTime?: number;
  }) {
    this.allItemsPassed = data.allItemsPassed;
    this.itemsPassed = data.itemsPassed;
    this.itemsTotal = data.itemsTotal;
    this.passRate = data.passRate;
    this.itemResults = data.itemResults;
    this.experimentId = data.experimentId;
    this.experimentName = data.experimentName;
    this.experimentUrl = data.experimentUrl;
    this.suiteName = data.suiteName;
    this.totalTime = data.totalTime;
  }

  /**
   * Convert the result to a structured report dictionary.
   *
   * The returned object mirrors the structure produced by the Python SDK's
   * `to_report_dict()` method (with camelCase keys per TypeScript conventions).
   */
  toReportDict(): Record<string, unknown> {
    const items: Record<string, unknown>[] = [];

    for (const [itemId, itemResult] of this.itemResults) {
      // Group test results by trialId to build one run entry per trial.
      const trialGroups = new Map<number, EvaluationTestResult[]>();
      for (const tr of itemResult.testResults) {
        const tid = tr.trialId ?? 0;
        const group = trialGroups.get(tid) ?? [];
        group.push(tr);
        trialGroups.set(tid, group);
      }

      const runs: Record<string, unknown>[] = [];
      for (const [trialId, trialResults] of trialGroups) {
        const allScores = trialResults.flatMap((tr) => tr.scoreResults);

        const assertions = allScores.map((score) => {
          const assertion: Record<string, unknown> = {
            name: score.name,
            passed: isScorePassed(score),
            value: score.value,
            scoringFailed: score.scoringFailed ?? false,
          };
          if (score.reason !== undefined) {
            assertion["reason"] = score.reason;
          }
          return assertion;
        });

        const runPassed =
          assertions.length === 0 || assertions.every((a) => a["passed"]);

        const firstResult = trialResults[0];
        const run: Record<string, unknown> = {
          trialId,
          passed: runPassed,
          input: firstResult.testCase.taskOutput["input"],
          output: firstResult.testCase.taskOutput["output"],
          assertions,
        };

        if (firstResult.testCase.traceId) {
          run["traceId"] = firstResult.testCase.traceId;
        }

        runs.push(run);
      }

      items.push({
        datasetItemId: itemId,
        passed: itemResult.passed,
        runsPassed: itemResult.runsPassed,
        executionPolicy: {
          runsPerItem: itemResult.configuredRunsPerItem,
          passThreshold: itemResult.passThreshold,
        },
        runs,
      });
    }

    const report: Record<string, unknown> = {
      suitePassed: this.allItemsPassed,
      itemsPassed: this.itemsPassed,
      itemsTotal: this.itemsTotal,
      passRate: this.passRate ?? null,
      experimentId: this.experimentId,
    };

    if (this.suiteName !== undefined) {
      report["suiteName"] = this.suiteName;
    }
    if (this.experimentName !== undefined) {
      report["experimentName"] = this.experimentName;
    }
    if (this.experimentUrl !== undefined) {
      report["experimentUrl"] = this.experimentUrl;
    }
    if (this.totalTime !== undefined) {
      report["totalTimeSeconds"] = Math.round(this.totalTime * 1000) / 1000;
    }

    report["generatedAt"] = new Date().toISOString();
    report["items"] = items;

    return report;
  }

  /** Alias for {@link toReportDict}. */
  toDict(): Record<string, unknown> {
    return this.toReportDict();
  }
}
