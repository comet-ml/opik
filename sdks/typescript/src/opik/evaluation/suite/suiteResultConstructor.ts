import { EvaluationResult, EvaluationTestResult } from "../types";
import {
  EvaluationSuiteResult,
  ItemResult,
  DEFAULT_EXECUTION_POLICY,
} from "./types";

/**
 * Determines if a single run (test result) passed.
 * A run passes if it has no score results, or ALL score results have truthy values.
 * In practice, value === 1 passes, value === 0 fails (matching Python's bool() semantics).
 */
function isRunPassing(testResult: EvaluationTestResult): boolean {
  if (testResult.scoreResults.length === 0) {
    return true;
  }
  return testResult.scoreResults.every((score) => Boolean(score.value));
}

/**
 * Groups test results by their dataset item ID.
 */
function groupByDatasetItemId(
  testResults: EvaluationTestResult[]
): Map<string, EvaluationTestResult[]> {
  const groups = new Map<string, EvaluationTestResult[]>();
  for (const result of testResults) {
    const itemId = result.testCase.datasetItemId;
    const group = groups.get(itemId);
    if (group) {
      group.push(result);
    } else {
      groups.set(itemId, [result]);
    }
  }
  return groups;
}

/**
 * Groups test results by their trial ID.
 * If no trialId is set, treats the result as trial 0.
 */
function groupByTrialId(
  testResults: EvaluationTestResult[]
): Map<number, EvaluationTestResult[]> {
  const groups = new Map<number, EvaluationTestResult[]>();
  for (const result of testResults) {
    const trialId = result.trialId ?? 0;
    const group = groups.get(trialId);
    if (group) {
      group.push(result);
    } else {
      groups.set(trialId, [result]);
    }
  }
  return groups;
}

/**
 * Builds an EvaluationSuiteResult from an EvaluationResult and execution policies.
 *
 * Pass/fail logic (matching Python exactly):
 * - Group test results by datasetItemId
 * - For each item, group runs by trialId (default to 0 if not set)
 * - A run passes if: no scoreResults, OR ALL scoreResults have truthy values
 * - Count runsPassed = number of passing runs
 * - Item passes if runsPassed >= passThreshold (from executionPolicies)
 * - allItemsPassed = ALL items pass
 * - passRate = itemsPassed / itemsTotal (1.0 if itemsTotal === 0)
 */
export function buildSuiteResult(
  evalResult: EvaluationResult
): EvaluationSuiteResult {
  const itemGroups = groupByDatasetItemId(evalResult.testResults);

  const itemResults = new Map<string, ItemResult>();

  for (const [itemId, testResults] of itemGroups) {
    const trialGroups = groupByTrialId(testResults);
    const runsTotal = trialGroups.size;

    let runsPassed = 0;
    for (const [, trialResults] of trialGroups) {
      // A run passes if ALL its test results pass
      const runPasses = trialResults.every(isRunPassing);
      if (runPasses) {
        runsPassed++;
      }
    }

    // Get policy from first test result (all runs for same item share the same policy)
    const firstResult = testResults[0];
    const policy =
      firstResult.resolvedExecutionPolicy ?? DEFAULT_EXECUTION_POLICY;
    const passThreshold = policy.passThreshold;
    const passed = runsPassed >= passThreshold;

    itemResults.set(itemId, {
      datasetItemId: itemId,
      passed,
      runsPassed,
      runsTotal,
      passThreshold,
      testResults,
    });
  }

  const itemsTotal = itemResults.size;
  let itemsPassed = 0;
  for (const [, itemResult] of itemResults) {
    if (itemResult.passed) {
      itemsPassed++;
    }
  }

  const allItemsPassed = itemsTotal === 0 || itemsPassed === itemsTotal;
  const passRate = itemsTotal === 0 ? 1.0 : itemsPassed / itemsTotal;

  return {
    allItemsPassed,
    itemsPassed,
    itemsTotal,
    passRate,
    itemResults,
    experimentId: evalResult.experimentId,
    experimentName: evalResult.experimentName,
    experimentUrl: evalResult.resultUrl,
  };
}
