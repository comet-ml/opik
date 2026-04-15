import { EvaluationResult, EvaluationTestResult } from "../types";
import {
  TestSuiteResult,
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
 * Builds a TestSuiteResult from an EvaluationResult and execution policies.
 *
 * Pass/fail logic (matching Python exactly):
 * - Group test results by datasetItemId
 * - For each item, group runs by trialId (default to 0 if not set)
 * - A run passes if: no scoreResults, OR ALL scoreResults have truthy values
 * - Count runsPassed = number of passing runs
 * - Item passes if runsPassed >= passThreshold (from executionPolicies)
 * - itemsTotal = ALL items (including those without assertions)
 * - allItemsPassed = itemsPassed === itemsTotal
 * - passRate = itemsPassed / itemsWithAssertions (undefined if none have assertions)
 */
export function buildSuiteResult(
  evalResult: EvaluationResult
): TestSuiteResult {
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
    const hasAssertions = testResults.some((tr) => tr.scoreResults.length > 0);

    itemResults.set(itemId, {
      datasetItemId: itemId,
      passed,
      hasAssertions,
      runsPassed,
      runsTotal,
      passThreshold,
      testResults,
    });
  }

  // itemsTotal and allItemsPassed count ALL items (matching Python's items_total / all_items_passed)
  const itemsTotal = itemResults.size;
  const itemsPassed = [...itemResults.values()].filter((r) => r.passed).length;
  const allItemsPassed = itemsTotal === 0 || itemsPassed === itemsTotal;

  // passRate is computed only over items that had at least one assertion evaluated
  // (matching Python's pass_rate property, which filters by has_assertions)
  const itemsWithAssertions = [...itemResults.values()].filter(
    (r) => r.hasAssertions
  );
  const passRate =
    itemsWithAssertions.length === 0
      ? undefined
      : itemsWithAssertions.filter((r) => r.passed).length /
        itemsWithAssertions.length;

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
