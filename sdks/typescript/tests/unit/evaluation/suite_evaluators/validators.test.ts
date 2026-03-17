import { describe, it, expect, vi } from "vitest";
import {
  resolveEvaluators,
  validateEvaluators,
  validateExecutionPolicy,
} from "@/evaluation/suite_evaluators/validators";
import { ExactMatch } from "@/evaluation/metrics/heuristics/ExactMatch";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";

// Mock model that doesn't need API keys
class MockModel extends OpikBaseModel {
  constructor() {
    super("mock-model");
  }
  async generateString(): Promise<string> {
    return "";
  }
  async generateProviderResponse(): Promise<unknown> {
    return {};
  }
}

// Mock resolveModel so LLMJudge constructor doesn't need real API keys
vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(() => new MockModel()),
}));

// Import LLMJudge AFTER mock is set up
import { LLMJudge } from "@/evaluation/suite_evaluators/LLMJudge";

describe("validateEvaluators", () => {
  it("should pass for LLMJudge instances", () => {
    const judge = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });
    expect(() => validateEvaluators([judge], "test")).not.toThrow();
  });

  it("should pass for multiple LLMJudge instances", () => {
    const judge1 = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });
    const judge2 = new LLMJudge({
      assertions: ["Output is concise"],
      track: false,
    });
    expect(() => validateEvaluators([judge1, judge2], "test")).not.toThrow();
  });

  it("should pass for an empty array", () => {
    expect(() => validateEvaluators([], "test")).not.toThrow();
  });

  it("should throw TypeError for plain objects", () => {
    const plainObj = { name: "fake", score: () => {} };
    expect(() => validateEvaluators([plainObj], "suite creation")).toThrow(
      TypeError
    );
    expect(() => validateEvaluators([plainObj], "suite creation")).toThrow(
      "Only LLMJudge evaluators are supported for suite creation"
    );
  });

  it("should throw TypeError for BaseMetric subclasses that are not LLMJudge", () => {
    const exactMatch = new ExactMatch("test_exact", false);
    expect(() =>
      validateEvaluators([exactMatch], "evaluation suite")
    ).toThrow(TypeError);
    expect(() =>
      validateEvaluators([exactMatch], "evaluation suite")
    ).toThrow("Received: ExactMatch");
  });

  it("should throw TypeError for mixed array (LLMJudge + non-LLMJudge)", () => {
    const judge = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });
    const exactMatch = new ExactMatch("test_exact", false);
    expect(() =>
      validateEvaluators([judge, exactMatch], "suite run")
    ).toThrow(TypeError);
  });

  it("should throw TypeError for primitive values", () => {
    expect(() => validateEvaluators(["not an evaluator"], "test")).toThrow(
      TypeError
    );
    expect(() => validateEvaluators(["not an evaluator"], "test")).toThrow(
      "Received: string"
    );
  });

  it("should throw TypeError for null values", () => {
    expect(() => validateEvaluators([null], "test")).toThrow(TypeError);
    expect(() => validateEvaluators([null], "test")).toThrow(
      "Received: object"
    );
  });

  it("should include context string in error message", () => {
    const plainObj = { name: "fake" };
    expect(() =>
      validateEvaluators([plainObj], "my custom context")
    ).toThrow("Only LLMJudge evaluators are supported for my custom context");
  });
});

describe("resolveEvaluators", () => {
  it("should return undefined when both assertions and evaluators are undefined", () => {
    expect(resolveEvaluators(undefined, undefined, "test")).toBeUndefined();
  });

  it("should return undefined when both assertions and evaluators are empty arrays", () => {
    expect(resolveEvaluators([], [], "test")).toBeUndefined();
  });

  it("should return a single LLMJudge when assertions are provided", () => {
    const result = resolveEvaluators(
      ["Output is relevant", "Output is concise"],
      undefined,
      "test"
    );

    expect(result).toHaveLength(1);
    expect(result![0]).toBeInstanceOf(LLMJudge);
    expect(result![0].assertions).toEqual([
      "Output is relevant",
      "Output is concise",
    ]);
  });

  it("should return given evaluators when evaluators are provided", () => {
    const judge = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });

    const result = resolveEvaluators(undefined, [judge], "test");

    expect(result).toEqual([judge]);
  });

  it("should call validateEvaluators when evaluators are provided", () => {
    const judge = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });

    // Should not throw (validateEvaluators passes for LLMJudge instances)
    expect(() => resolveEvaluators(undefined, [judge], "my context")).not.toThrow();
  });

  it("should throw when both assertions and evaluators are provided", () => {
    const judge = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });

    expect(() =>
      resolveEvaluators(["some assertion"], [judge], "test context")
    ).toThrow(
      "Cannot specify both 'assertions' and 'evaluators' for test context"
    );
  });

  it("should include context string in error message", () => {
    const judge = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });

    expect(() =>
      resolveEvaluators(["assertion"], [judge], "my custom context")
    ).toThrow("for my custom context");
  });
});

describe("validateExecutionPolicy", () => {
  it("should pass for valid policy { runsPerItem: 3, passThreshold: 2 }", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 3, passThreshold: 2 }, "test")
    ).not.toThrow();
  });

  it("should pass for partial policy { runsPerItem: 2 }", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 2 }, "test")
    ).not.toThrow();
  });

  it("should pass for partial policy { passThreshold: 1 }", () => {
    expect(() =>
      validateExecutionPolicy({ passThreshold: 1 }, "test")
    ).not.toThrow();
  });

  it("should pass for empty policy (both fields undefined)", () => {
    expect(() => validateExecutionPolicy({}, "test")).not.toThrow();
  });

  it("should pass when passThreshold equals runsPerItem", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 3, passThreshold: 3 }, "test")
    ).not.toThrow();
  });

  it("should throw RangeError for runsPerItem: 0", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 0 }, "test")
    ).toThrow(
      expect.objectContaining({
        name: "RangeError",
        message: expect.stringContaining("runsPerItem must be a positive integer"),
      })
    );
  });

  it("should throw RangeError for runsPerItem: -1", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: -1 }, "test")
    ).toThrow(RangeError);
  });

  it("should throw RangeError for runsPerItem: 1.5 (non-integer)", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 1.5 }, "test")
    ).toThrow(
      expect.objectContaining({
        name: "RangeError",
        message: expect.stringContaining("runsPerItem must be a positive integer"),
      })
    );
  });

  it("should throw RangeError for passThreshold: 0", () => {
    expect(() =>
      validateExecutionPolicy({ passThreshold: 0 }, "test")
    ).toThrow(
      expect.objectContaining({
        name: "RangeError",
        message: expect.stringContaining("passThreshold must be a positive integer"),
      })
    );
  });

  it("should throw RangeError for passThreshold > runsPerItem", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 2, passThreshold: 5 }, "test")
    ).toThrow(
      expect.objectContaining({
        name: "RangeError",
        message: expect.stringContaining(
          "passThreshold (5) cannot exceed runsPerItem (2)"
        ),
      })
    );
  });

  it("should include context string in error message", () => {
    expect(() =>
      validateExecutionPolicy({ runsPerItem: 0 }, "my custom context")
    ).toThrow("for my custom context");
  });
});

describe("LLMJudge.merged", () => {
  it("should return undefined for a single judge", () => {
    const judge1 = new LLMJudge({
      assertions: ["Output is relevant"],
      track: false,
    });
    expect(LLMJudge.merged([judge1])).toBeUndefined();
  });

  it("should return undefined for an empty array", () => {
    expect(LLMJudge.merged([])).toBeUndefined();
  });

  it("should merge judges with the same settings and combine unique assertions", () => {
    const judge1 = new LLMJudge({
      assertions: ["Output is relevant"],
      model: "gpt-4o",
      temperature: 0.5,
      seed: 42,
      track: false,
    });
    const judge2 = new LLMJudge({
      assertions: ["Output is concise"],
      model: "gpt-4o",
      temperature: 0.5,
      seed: 42,
      track: false,
    });

    const merged = LLMJudge.merged([judge1, judge2]);

    expect(merged).toBeInstanceOf(LLMJudge);
    expect(merged!.assertions).toContain("Output is relevant");
    expect(merged!.assertions).toContain("Output is concise");
    expect(merged!.assertions).toHaveLength(2);
  });

  it("should return undefined when judges have different track settings", () => {
    const judge1 = new LLMJudge({
      assertions: ["Output is relevant"],
      track: true,
    });
    const judge2 = new LLMJudge({
      assertions: ["Output is concise"],
      track: false,
    });

    expect(LLMJudge.merged([judge1, judge2])).toBeUndefined();
  });

  it("should return undefined when judges have different models", () => {
    const judge1 = new LLMJudge({
      assertions: ["Output is relevant"],
      model: "gpt-4o",
      track: false,
    });
    const judge2 = new LLMJudge({
      assertions: ["Output is concise"],
      model: "gpt-4o-mini",
      track: false,
    });

    expect(LLMJudge.merged([judge1, judge2])).toBeUndefined();
  });

  it("should deduplicate assertions when merging judges with overlapping assertions", () => {
    const judge1 = new LLMJudge({
      assertions: ["Output is relevant", "Output is concise"],
      track: false,
    });
    const judge2 = new LLMJudge({
      assertions: ["Output is concise", "Output is accurate"],
      track: false,
    });

    const merged = LLMJudge.merged([judge1, judge2]);

    expect(merged).toBeInstanceOf(LLMJudge);
    expect(merged!.assertions).toEqual([
      "Output is relevant",
      "Output is concise",
      "Output is accurate",
    ]);
    expect(merged!.assertions).toHaveLength(3);
  });
});
