import { describe, it, expect } from "vitest";
import { buildSuiteResult } from "@/evaluation/suite/suiteResultConstructor";
import {
  EvaluationResult,
  EvaluationTestResult,
  EvaluationScoreResult,
  EvaluationTestCase,
} from "@/evaluation/types";

function makeTestCase(
  datasetItemId: string,
  traceId: string = "trace-1"
): EvaluationTestCase {
  return {
    traceId,
    datasetItemId,
    scoringInputs: {},
    taskOutput: {},
  };
}

function makeScoreResult(
  name: string,
  value: number,
  reason?: string
): EvaluationScoreResult {
  return { name, value, ...(reason !== undefined && { reason }) };
}

function makeTestResult(
  datasetItemId: string,
  scoreResults: EvaluationScoreResult[],
  trialId?: number,
  traceId: string = "trace-1",
  resolvedExecutionPolicy?: { runsPerItem: number; passThreshold: number }
): EvaluationTestResult {
  return {
    testCase: makeTestCase(datasetItemId, traceId),
    scoreResults,
    ...(trialId !== undefined && { trialId }),
    ...(resolvedExecutionPolicy !== undefined && { resolvedExecutionPolicy }),
  };
}

function makeEvalResult(
  testResults: EvaluationTestResult[],
  experimentId: string = "exp-1"
): EvaluationResult {
  return {
    experimentId,
    testResults,
    errors: [],
  };
}

const DEFAULT_POLICY = { runsPerItem: 1, passThreshold: 1 };

describe("buildSuiteResult", () => {
  describe("single item, all assertions pass", () => {
    it("should mark item as passed when all score results have value=1", () => {
      const testResults = [
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
            makeScoreResult("metric-c", 1),
          ],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(true);
      expect(result.itemsPassed).toBe(1);
      expect(result.itemsTotal).toBe(1);
      expect(result.passRate).toBe(1.0);
      expect(result.experimentId).toBe("exp-1");

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(true);
      expect(itemResult!.runsPassed).toBe(1);
      expect(itemResult!.runsTotal).toBe(1);
      expect(itemResult!.passThreshold).toBe(1);
    });
  });

  describe("single item, one assertion fails", () => {
    it("should mark item as failed when any score result has value=0", () => {
      const testResults = [
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
            makeScoreResult("metric-c", 0),
          ],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(false);
      expect(result.itemsPassed).toBe(0);
      expect(result.passRate).toBe(0.0);

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(false);
      expect(itemResult!.runsPassed).toBe(0);
    });
  });

  describe("multiple runs, threshold met", () => {
    it("should pass item when runsPassed >= passThreshold", () => {
      const policy = { runsPerItem: 3, passThreshold: 2 };
      const testResults = [
        // Run 0: passes (all scores = 1)
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
          ],
          0,
          "trace-1",
          policy
        ),
        // Run 1: fails (has a value=0 score)
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 0),
          ],
          1,
          "trace-1",
          policy
        ),
        // Run 2: passes (all scores = 1)
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
          ],
          2,
          "trace-1",
          policy
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(true);

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(true);
      expect(itemResult!.runsPassed).toBe(2);
      expect(itemResult!.runsTotal).toBe(3);
    });
  });

  describe("multiple runs, threshold not met", () => {
    it("should fail item when runsPassed < passThreshold", () => {
      const policy = { runsPerItem: 3, passThreshold: 2 };
      const testResults = [
        // Run 0: fails
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 0),
            makeScoreResult("metric-b", 1),
          ],
          0,
          "trace-1",
          policy
        ),
        // Run 1: fails
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 0),
          ],
          1,
          "trace-1",
          policy
        ),
        // Run 2: passes
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
          ],
          2,
          "trace-1",
          policy
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(false);

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(false);
      expect(itemResult!.runsPassed).toBe(1);
      expect(itemResult!.runsTotal).toBe(3);
    });
  });

  describe("no scores → run passes by default", () => {
    it("should pass a run with empty scoreResults", () => {
      const testResults = [
        makeTestResult("item-1", [], undefined, "trace-1", DEFAULT_POLICY),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(true);

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(true);
      expect(itemResult!.runsPassed).toBe(1);
    });
  });

  describe("multiple items, partial pass", () => {
    it("should correctly compute pass rate with some items failing", () => {
      const testResults = [
        // Item 1: passes
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
          ],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
        // Item 2: passes
        makeTestResult(
          "item-2",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 1),
          ],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
        // Item 3: fails
        makeTestResult(
          "item-3",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 0),
          ],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(false);
      expect(result.itemsPassed).toBe(2);
      expect(result.itemsTotal).toBe(3);
      expect(result.passRate).toBeCloseTo(0.667, 2);
    });
  });

  describe("integer scores: 1 = pass", () => {
    it("should treat score value 1 as passing", () => {
      const testResults = [
        makeTestResult(
          "item-1",
          [makeScoreResult("metric-a", 1)],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(true);
    });
  });

  describe("integer scores: 0 = fail", () => {
    it("should treat score value 0 as failing", () => {
      const testResults = [
        makeTestResult(
          "item-1",
          [
            makeScoreResult("metric-a", 1),
            makeScoreResult("metric-b", 0),
          ],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
      ];
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      const itemResult = result.itemResults.get("item-1");
      expect(itemResult).toBeDefined();
      expect(itemResult!.passed).toBe(false);
    });
  });

  describe("pass rate: all pass", () => {
    it("should return passRate=1.0 when all items pass", () => {
      const testResults = Array.from({ length: 5 }, (_, i) =>
        makeTestResult(
          `item-${i}`,
          [makeScoreResult("metric-a", 1)],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        )
      );
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.passRate).toBe(1.0);
      expect(result.itemsPassed).toBe(5);
      expect(result.itemsTotal).toBe(5);
    });
  });

  describe("pass rate: none pass", () => {
    it("should return passRate=0.0 when no items pass", () => {
      const testResults = Array.from({ length: 5 }, (_, i) =>
        makeTestResult(
          `item-${i}`,
          [makeScoreResult("metric-a", 0)],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        )
      );
      const evalResult = makeEvalResult(testResults);

      const result = buildSuiteResult(evalResult);

      expect(result.passRate).toBe(0.0);
      expect(result.itemsPassed).toBe(0);
      expect(result.itemsTotal).toBe(5);
    });
  });

  describe("pass rate: zero items", () => {
    it("should return passRate=undefined and allItemsPassed=true when no items exist", () => {
      const evalResult = makeEvalResult([]);

      const result = buildSuiteResult(evalResult);

      expect(result.allItemsPassed).toBe(true);
      expect(result.passRate).toBeUndefined();
      expect(result.itemsTotal).toBe(0);
      expect(result.itemsPassed).toBe(0);
    });
  });

  describe("hasAssertions flag", () => {
    it("should set hasAssertions=false for items with no score results", () => {
      const testResults = [
        makeTestResult("item-1", [], undefined, "trace-1", DEFAULT_POLICY),
      ];
      const result = buildSuiteResult(makeEvalResult(testResults));

      expect(result.itemResults.get("item-1")!.hasAssertions).toBe(false);
    });

    it("should set hasAssertions=true for items with at least one score result", () => {
      const testResults = [
        makeTestResult(
          "item-1",
          [makeScoreResult("metric-a", 1)],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
      ];
      const result = buildSuiteResult(makeEvalResult(testResults));

      expect(result.itemResults.get("item-1")!.hasAssertions).toBe(true);
    });
  });

  describe("passRate uses only items with assertions; itemsTotal counts all", () => {
    it("should return passRate=undefined when all items have no assertions, but itemsTotal counts them", () => {
      const testResults = [
        makeTestResult("item-1", [], undefined, "trace-1", DEFAULT_POLICY),
        makeTestResult("item-2", [], undefined, "trace-2", DEFAULT_POLICY),
      ];
      const result = buildSuiteResult(makeEvalResult(testResults));

      // itemsTotal and allItemsPassed count ALL items
      expect(result.itemsTotal).toBe(2);
      expect(result.itemsPassed).toBe(2); // vacuously pass
      expect(result.allItemsPassed).toBe(true);
      // passRate is undefined because no item had assertions
      expect(result.passRate).toBeUndefined();
    });

    it("should compute passRate only over items with assertions in a mixed suite", () => {
      const testResults = [
        // item with assertions — passes
        makeTestResult(
          "item-with-pass",
          [makeScoreResult("metric-a", 1)],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
        // item with assertions — fails
        makeTestResult(
          "item-with-fail",
          [makeScoreResult("metric-a", 0)],
          undefined,
          "trace-2",
          DEFAULT_POLICY
        ),
        // item without assertions — excluded from passRate, but counted in itemsTotal
        makeTestResult("item-no-assertions", [], undefined, "trace-3", DEFAULT_POLICY),
      ];
      const result = buildSuiteResult(makeEvalResult(testResults));

      // itemsTotal counts all 3
      expect(result.itemsTotal).toBe(3);
      // itemsPassed: item-with-pass passes, item-no-assertions pass vacuously, item-with-fail fails
      expect(result.itemsPassed).toBe(2);
      expect(result.allItemsPassed).toBe(false);
      // passRate only over the 2 items with assertions: 1/2
      expect(result.passRate).toBeCloseTo(0.5, 5);
    });

    it("should compute passRate over all items when all have assertions (global assertions case)", () => {
      const testResults = [
        makeTestResult(
          "item-1",
          [makeScoreResult("global", 1)],
          undefined,
          "trace-1",
          DEFAULT_POLICY
        ),
        makeTestResult(
          "item-2",
          [makeScoreResult("global", 0)],
          undefined,
          "trace-2",
          DEFAULT_POLICY
        ),
      ];
      const result = buildSuiteResult(makeEvalResult(testResults));

      expect(result.itemsTotal).toBe(2);
      expect(result.passRate).toBeCloseTo(0.5, 5);
    });
  });
});
