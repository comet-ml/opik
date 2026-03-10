import { describe, it, expect } from "vitest";
import {
  buildResponseSchema,
  parseResponse,
} from "@/evaluation/suite_evaluators/llmJudgeParsers";

describe("buildResponseSchema", () => {
  it("should generate a Zod schema with one field per assertion", () => {
    const assertions = ["Output is relevant", "Output is concise"];
    const schema = buildResponseSchema(assertions);

    // The schema should parse a valid object
    const valid = {
      "Output is relevant": { score: true, reason: "Good", confidence: 0.9 },
      "Output is concise": { score: false, reason: "Too long", confidence: 0.7 },
    };

    const result = schema.safeParse(valid);
    expect(result.success).toBe(true);
  });

  it("should reject objects missing assertion fields", () => {
    const assertions = ["Output is relevant", "Output is concise"];
    const schema = buildResponseSchema(assertions);

    const invalid = {
      "Output is relevant": { score: true, reason: "Good", confidence: 0.9 },
      // Missing "Output is concise"
    };

    const result = schema.safeParse(invalid);
    expect(result.success).toBe(false);
  });

  it("should reject assertion fields with missing sub-fields", () => {
    const assertions = ["Output is relevant"];
    const schema = buildResponseSchema(assertions);

    const invalid = {
      "Output is relevant": { score: true },
      // Missing reason and confidence
    };

    const result = schema.safeParse(invalid);
    expect(result.success).toBe(false);
  });

  it("should reject confidence values outside 0-1 range", () => {
    const assertions = ["Output is relevant"];
    const schema = buildResponseSchema(assertions);

    const invalid = {
      "Output is relevant": {
        score: true,
        reason: "Good",
        confidence: 1.5,
      },
    };

    const result = schema.safeParse(invalid);
    expect(result.success).toBe(false);
  });

  it("should handle a single assertion", () => {
    const assertions = ["Is polite"];
    const schema = buildResponseSchema(assertions);

    const valid = {
      "Is polite": { score: true, reason: "Very polite", confidence: 0.95 },
    };

    const result = schema.safeParse(valid);
    expect(result.success).toBe(true);
  });

  it("should handle empty assertions array", () => {
    const assertions: string[] = [];
    const schema = buildResponseSchema(assertions);

    const result = schema.safeParse({});
    expect(result.success).toBe(true);
  });
});

describe("parseResponse", () => {
  it("should return correct ScoreResult[] for a valid response", () => {
    const assertions = ["Output is relevant", "Output is concise"];
    const response = {
      "Output is relevant": {
        score: true,
        reason: "Matches the topic",
        confidence: 0.9,
      },
      "Output is concise": {
        score: false,
        reason: "Too verbose",
        confidence: 0.8,
      },
    };

    const results = parseResponse(response, assertions);

    expect(results).toHaveLength(2);

    expect(results[0]).toEqual({
      name: "Output is relevant",
      value: 1,
      reason: "Matches the topic",
      categoryName: "suite_assertion",
    });
    expect(results[1]).toEqual({
      name: "Output is concise",
      value: 0,
      reason: "Too verbose",
      categoryName: "suite_assertion",
    });
  });

  it("should convert boolean true to value 1", () => {
    const assertions = ["Is correct"];
    const response = {
      "Is correct": { score: true, reason: "Yes", confidence: 1.0 },
    };

    const results = parseResponse(response, assertions);
    expect(results[0].value).toBe(1);
    expect(results[0].categoryName).toBe("suite_assertion");
  });

  it("should convert boolean false to value 0", () => {
    const assertions = ["Is correct"];
    const response = {
      "Is correct": { score: false, reason: "No", confidence: 0.5 },
    };

    const results = parseResponse(response, assertions);
    expect(results[0].value).toBe(0);
    expect(results[0].categoryName).toBe("suite_assertion");
  });

  it("should return scoringFailed: true for missing fields", () => {
    const assertions = ["Output is relevant", "Output is concise"];
    const response = {
      "Output is relevant": {
        score: true,
        reason: "Good",
        confidence: 0.9,
      },
      // "Output is concise" is missing
    };

    const results = parseResponse(response, assertions);

    expect(results).toHaveLength(2);
    expect(results[0].scoringFailed).toBeUndefined();
    expect(results[1]).toEqual({
      name: "Output is concise",
      value: 0,
      reason: expect.stringContaining("missing"),
      scoringFailed: true,
      categoryName: "suite_assertion",
    });
  });

  it("should return scoringFailed: true for malformed assertion data", () => {
    const assertions = ["Is correct"];
    const response = {
      "Is correct": "not an object",
    };

    const results = parseResponse(response, assertions);

    expect(results[0]).toEqual({
      name: "Is correct",
      value: 0,
      reason: expect.stringContaining("malformed"),
      scoringFailed: true,
      categoryName: "suite_assertion",
    });
  });

  it("should preserve assertion order in results", () => {
    const assertions = ["Third", "First", "Second"];
    const response = {
      First: { score: true, reason: "r1", confidence: 0.9 },
      Second: { score: false, reason: "r2", confidence: 0.8 },
      Third: { score: true, reason: "r3", confidence: 0.7 },
    };

    const results = parseResponse(response, assertions);

    expect(results[0].name).toBe("Third");
    expect(results[1].name).toBe("First");
    expect(results[2].name).toBe("Second");
  });

  it("should use assertion text as the name for each result", () => {
    const assertions = ["The output addresses all user concerns"];
    const response = {
      "The output addresses all user concerns": {
        score: true,
        reason: "All concerns addressed",
        confidence: 0.95,
      },
    };

    const results = parseResponse(response, assertions);
    expect(results[0].name).toBe("The output addresses all user concerns");
  });

  it("should handle empty assertions array", () => {
    const results = parseResponse({}, []);
    expect(results).toEqual([]);
  });
});
