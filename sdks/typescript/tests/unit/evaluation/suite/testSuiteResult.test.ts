import { describe, it, expect } from "vitest";
import { buildSuiteResult } from "@/evaluation/suite/suiteResultConstructor";
import {
  EvaluationResult,
  EvaluationTestResult,
  EvaluationScoreResult,
  EvaluationTestCase,
} from "@/evaluation/types";

// ---------------------------------------------------------------------------
// Helpers (mirrors suiteResultConstructor.test.ts)
// ---------------------------------------------------------------------------

function makeTestCase(
  datasetItemId: string,
  taskOutput: Record<string, unknown> = {},
  traceId: string = `trace-${datasetItemId}`
): EvaluationTestCase {
  return { traceId, datasetItemId, scoringInputs: {}, taskOutput };
}

function makeScore(
  name: string,
  value: number,
  opts: { reason?: string; scoringFailed?: boolean } = {}
): EvaluationScoreResult {
  return {
    name,
    value,
    ...(opts.reason !== undefined && { reason: opts.reason }),
    ...(opts.scoringFailed !== undefined && {
      scoringFailed: opts.scoringFailed,
    }),
  };
}

function makeTestResult(
  datasetItemId: string,
  scoreResults: EvaluationScoreResult[],
  opts: {
    trialId?: number;
    taskOutput?: Record<string, unknown>;
    traceId?: string;
    resolvedExecutionPolicy?: { runsPerItem: number; passThreshold: number };
  } = {}
): EvaluationTestResult {
  const {
    trialId,
    taskOutput = { input: "q", output: "a" },
    traceId,
    resolvedExecutionPolicy,
  } = opts;
  return {
    testCase: makeTestCase(datasetItemId, taskOutput, traceId),
    scoreResults,
    ...(trialId !== undefined && { trialId }),
    ...(resolvedExecutionPolicy !== undefined && { resolvedExecutionPolicy }),
  };
}

function makeEvalResult(
  testResults: EvaluationTestResult[],
  opts: {
    experimentId?: string;
    experimentName?: string;
    resultUrl?: string;
  } = {}
): EvaluationResult {
  return {
    experimentId: opts.experimentId ?? "exp-1",
    testResults,
    errors: [],
    ...(opts.experimentName !== undefined && {
      experimentName: opts.experimentName,
    }),
    ...(opts.resultUrl !== undefined && { resultUrl: opts.resultUrl }),
  };
}

const DEFAULT_POLICY = { runsPerItem: 1, passThreshold: 1 };

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("TestSuiteResult.toReportDict", () => {
  describe("top-level fields", () => {
    it("includes required fields when no optional data is set", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("m", 1)], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );

      const dict = result.toReportDict();

      expect(dict).toMatchObject({
        suitePassed: true,
        itemsPassed: 1,
        itemsTotal: 1,
        passRate: 1,
        experimentId: "exp-1",
      });
      expect(typeof dict["generatedAt"]).toBe("string");
      expect(Array.isArray(dict["items"])).toBe(true);
    });

    it("omits suiteName when not provided", () => {
      const result = buildSuiteResult(makeEvalResult([]));
      expect(result.toReportDict()).not.toHaveProperty("suiteName");
    });

    it("includes suiteName when provided", () => {
      const result = buildSuiteResult(makeEvalResult([]), {
        suiteName: "My Suite",
      });
      expect(result.toReportDict()["suiteName"]).toBe("My Suite");
    });

    it("omits totalTimeSeconds when not provided", () => {
      const result = buildSuiteResult(makeEvalResult([]));
      expect(result.toReportDict()).not.toHaveProperty("totalTimeSeconds");
    });

    it("includes totalTimeSeconds rounded to 3 decimal places", () => {
      const result = buildSuiteResult(makeEvalResult([]), {
        totalTime: 12.3456,
      });
      expect(result.toReportDict()["totalTimeSeconds"]).toBe(12.346);
    });

    it("includes experimentName and experimentUrl when present", () => {
      const result = buildSuiteResult(
        makeEvalResult([], {
          experimentName: "run-42",
          resultUrl: "https://example.com/exp",
        })
      );

      const dict = result.toReportDict();
      expect(dict["experimentName"]).toBe("run-42");
      expect(dict["experimentUrl"]).toBe("https://example.com/exp");
    });

    it("omits experimentName and experimentUrl when absent", () => {
      const result = buildSuiteResult(makeEvalResult([]));
      const dict = result.toReportDict();
      expect(dict).not.toHaveProperty("experimentName");
      expect(dict).not.toHaveProperty("experimentUrl");
    });

    it("sets passRate to null when no items have assertions", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );
      expect(result.toReportDict()["passRate"]).toBeNull();
    });
  });

  describe("item structure", () => {
    it("produces one item entry per dataset item", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("m", 1)], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
          makeTestResult("item-2", [makeScore("m", 0)], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );

      const items = result.toReportDict()["items"] as Record<
        string,
        unknown
      >[];
      expect(items).toHaveLength(2);
    });

    it("item includes datasetItemId, passed, runsPassed, executionPolicy and runs", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("m", 1)], {
            resolvedExecutionPolicy: { runsPerItem: 2, passThreshold: 1 },
          }),
        ])
      );

      const item = (
        result.toReportDict()["items"] as Record<string, unknown>[]
      )[0];

      expect(item["datasetItemId"]).toBe("item-1");
      expect(item["passed"]).toBe(true);
      expect(item["runsPassed"]).toBe(1);
      expect(item["executionPolicy"]).toEqual({
        runsPerItem: 2,
        passThreshold: 1,
      });
    });
  });

  describe("run structure", () => {
    it("single run: fields are populated from taskOutput and testCase", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("check", 1)], {
            trialId: 0,
            taskOutput: { input: "hello", output: "world" },
            traceId: "trace-abc",
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );

      const item = (
        result.toReportDict()["items"] as Record<string, unknown>[]
      )[0];
      const runs = item["runs"] as Record<string, unknown>[];

      expect(runs).toHaveLength(1);
      expect(runs[0]["trialId"]).toBe(0);
      expect(runs[0]["passed"]).toBe(true);
      expect(runs[0]["input"]).toBe("hello");
      expect(runs[0]["output"]).toBe("world");
      expect(runs[0]["traceId"]).toBe("trace-abc");
    });

    it("run with all assertions passing → passed: true", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult(
            "item-1",
            [makeScore("a", 1), makeScore("b", 1)],
            { resolvedExecutionPolicy: DEFAULT_POLICY }
          ),
        ])
      );

      const run = (
        (result.toReportDict()["items"] as Record<string, unknown>[])[0][
          "runs"
        ] as Record<string, unknown>[]
      )[0];
      expect(run["passed"]).toBe(true);
    });

    it("run with any assertion failing → passed: false", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult(
            "item-1",
            [makeScore("a", 1), makeScore("b", 0)],
            { resolvedExecutionPolicy: DEFAULT_POLICY }
          ),
        ])
      );

      const run = (
        (result.toReportDict()["items"] as Record<string, unknown>[])[0][
          "runs"
        ] as Record<string, unknown>[]
      )[0];
      expect(run["passed"]).toBe(false);
    });

    it("run with no assertions → passed: true", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );

      const run = (
        (result.toReportDict()["items"] as Record<string, unknown>[])[0][
          "runs"
        ] as Record<string, unknown>[]
      )[0];
      expect(run["passed"]).toBe(true);
    });

    it("groups by trialId: multi-run item produces one run entry per trial", () => {
      const policy = { runsPerItem: 3, passThreshold: 2 };
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("m", 1)], {
            trialId: 0,
            resolvedExecutionPolicy: policy,
          }),
          makeTestResult("item-1", [makeScore("m", 0)], {
            trialId: 1,
            resolvedExecutionPolicy: policy,
          }),
          makeTestResult("item-1", [makeScore("m", 1)], {
            trialId: 2,
            resolvedExecutionPolicy: policy,
          }),
        ])
      );

      const runs = (
        result.toReportDict()["items"] as Record<string, unknown>[]
      )[0]["runs"] as Record<string, unknown>[];

      expect(runs).toHaveLength(3);
      expect(runs.map((r) => r["trialId"])).toEqual([0, 1, 2]);
      expect(runs.map((r) => r["passed"])).toEqual([true, false, true]);
    });
  });

  describe("assertion structure", () => {
    it("assertion includes name, passed, value, scoringFailed", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("is-correct", 1)], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );

      const assertion = (
        (
          (result.toReportDict()["items"] as Record<string, unknown>[])[0][
            "runs"
          ] as Record<string, unknown>[]
        )[0]["assertions"] as Record<string, unknown>[]
      )[0];

      expect(assertion["name"]).toBe("is-correct");
      expect(assertion["passed"]).toBe(true);
      expect(assertion["value"]).toBe(1);
      expect(assertion["scoringFailed"]).toBe(false);
    });

    it("value=1 → passed:true; value=0 → passed:false", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult(
            "item-1",
            [makeScore("pass-check", 1), makeScore("fail-check", 0)],
            { resolvedExecutionPolicy: DEFAULT_POLICY }
          ),
        ])
      );

      const assertions = (
        (
          (result.toReportDict()["items"] as Record<string, unknown>[])[0][
            "runs"
          ] as Record<string, unknown>[]
        )[0]["assertions"] as Record<string, unknown>[]
      );

      expect(assertions[0]["passed"]).toBe(true);
      expect(assertions[1]["passed"]).toBe(false);
    });

    it("scoringFailed:true → passed:false regardless of value", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult(
            "item-1",
            [makeScore("flaky", 1, { scoringFailed: true, reason: "timeout" })],
            { resolvedExecutionPolicy: DEFAULT_POLICY }
          ),
        ])
      );

      const assertion = (
        (
          (result.toReportDict()["items"] as Record<string, unknown>[])[0][
            "runs"
          ] as Record<string, unknown>[]
        )[0]["assertions"] as Record<string, unknown>[]
      )[0];

      expect(assertion["passed"]).toBe(false);
      expect(assertion["scoringFailed"]).toBe(true);
      expect(assertion["reason"]).toBe("timeout");
    });

    it("omits reason when not set", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult("item-1", [makeScore("m", 1)], {
            resolvedExecutionPolicy: DEFAULT_POLICY,
          }),
        ])
      );

      const assertion = (
        (
          (result.toReportDict()["items"] as Record<string, unknown>[])[0][
            "runs"
          ] as Record<string, unknown>[]
        )[0]["assertions"] as Record<string, unknown>[]
      )[0];

      expect(assertion).not.toHaveProperty("reason");
    });

    it("includes reason when set", () => {
      const result = buildSuiteResult(
        makeEvalResult([
          makeTestResult(
            "item-1",
            [makeScore("m", 0, { reason: "too verbose" })],
            { resolvedExecutionPolicy: DEFAULT_POLICY }
          ),
        ])
      );

      const assertion = (
        (
          (result.toReportDict()["items"] as Record<string, unknown>[])[0][
            "runs"
          ] as Record<string, unknown>[]
        )[0]["assertions"] as Record<string, unknown>[]
      )[0];

      expect(assertion["reason"]).toBe("too verbose");
    });
  });
});

describe("TestSuiteResult.toDict", () => {
  it("returns the same object as toReportDict", () => {
    const result = buildSuiteResult(
      makeEvalResult([
        makeTestResult("item-1", [makeScore("m", 1)], {
          resolvedExecutionPolicy: DEFAULT_POLICY,
        }),
      ]),
      { suiteName: "s", totalTime: 1.5 }
    );

    const dictResult = result.toDict();
    const reportDictResult = result.toReportDict();

    // toDict is an alias — same keys and values (generatedAt will differ by ms)
    expect(Object.keys(dictResult).sort()).toEqual(
      Object.keys(reportDictResult).sort()
    );
    expect(dictResult["suitePassed"]).toBe(reportDictResult["suitePassed"]);
    expect(dictResult["suiteName"]).toBe(reportDictResult["suiteName"]);
    expect(dictResult["totalTimeSeconds"]).toBe(
      reportDictResult["totalTimeSeconds"]
    );
  });
});