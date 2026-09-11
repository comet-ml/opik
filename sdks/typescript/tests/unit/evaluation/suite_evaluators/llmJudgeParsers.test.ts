import { describe, it, expect } from "vitest";
import { ResponseSchema } from "@/evaluation/suite_evaluators/llmJudgeParsers";

describe("ResponseSchema", () => {
  describe("responseSchema", () => {
    it("should generate a Zod schema with indexed keys for assertions", () => {
      const schema = new ResponseSchema([
        "Output is relevant",
        "Output is concise",
      ]);

      const valid = {
        assertion_1: { score: true, reason: "Good", confidence: 0.9 },
        assertion_2: { score: false, reason: "Too long", confidence: 0.7 },
      };

      const result = schema.responseSchema.safeParse(valid);
      expect(result.success).toBe(true);
    });

    it("should reject objects missing assertion fields", () => {
      const schema = new ResponseSchema([
        "Output is relevant",
        "Output is concise",
      ]);

      const invalid = {
        assertion_1: { score: true, reason: "Good", confidence: 0.9 },
      };

      const result = schema.responseSchema.safeParse(invalid);
      expect(result.success).toBe(false);
    });

    it("should reject assertion fields with missing sub-fields", () => {
      const schema = new ResponseSchema(["Output is relevant"]);

      const invalid = {
        assertion_1: { score: true },
      };

      const result = schema.responseSchema.safeParse(invalid);
      expect(result.success).toBe(false);
    });

    it("should reject confidence values outside 0-1 range", () => {
      const schema = new ResponseSchema(["Output is relevant"]);

      const invalid = {
        assertion_1: {
          score: true,
          reason: "Good",
          confidence: 1.5,
        },
      };

      const result = schema.responseSchema.safeParse(invalid);
      expect(result.success).toBe(false);
    });

    it("should handle a single assertion", () => {
      const schema = new ResponseSchema(["Is polite"]);

      const valid = {
        assertion_1: {
          score: true,
          reason: "Very polite",
          confidence: 0.95,
        },
      };

      const result = schema.responseSchema.safeParse(valid);
      expect(result.success).toBe(true);
    });

    it("should handle empty assertions array", () => {
      const schema = new ResponseSchema([]);

      const result = schema.responseSchema.safeParse({});
      expect(result.success).toBe(true);
    });

    it("should use short identifier keys regardless of assertion text length", () => {
      const schema = new ResponseSchema([
        "A very long assertion that would previously create a huge key name",
        'Special chars: {}/\\"quotes"',
      ]);

      const valid = {
        assertion_1: { score: true, reason: "OK", confidence: 0.9 },
        assertion_2: { score: false, reason: "Nope", confidence: 0.5 },
      };

      const result = schema.responseSchema.safeParse(valid);
      expect(result.success).toBe(true);
    });

    it("should create all fields for many assertions", () => {
      const assertions = Array.from(
        { length: 10 },
        (_, i) => `Assertion number ${i + 1}`
      );
      const schema = new ResponseSchema(assertions);

      const valid: Record<string, unknown> = {};
      for (let i = 1; i <= 10; i++) {
        valid[`assertion_${i}`] = {
          score: true,
          reason: `Reason ${i}`,
          confidence: 0.9,
        };
      }

      const result = schema.responseSchema.safeParse(valid);
      expect(result.success).toBe(true);
    });
  });

  describe("formatAssertions", () => {
    it("should format assertions with indexed keys", () => {
      const schema = new ResponseSchema([
        "Response is accurate",
        "No hallucinations",
      ]);

      const formatted = schema.formatAssertions();

      expect(formatted).toContain("- `assertion_1`: Response is accurate");
      expect(formatted).toContain("- `assertion_2`: No hallucinations");
    });

    it("should list all assertions for many items", () => {
      const assertions = Array.from(
        { length: 7 },
        (_, i) => `Check item ${i + 1}`
      );
      const schema = new ResponseSchema(assertions);

      const formatted = schema.formatAssertions();

      for (let i = 1; i <= 7; i++) {
        expect(formatted).toContain(`- \`assertion_${i}\`: Check item ${i}`);
      }
    });
  });

  describe("parse", () => {
    it("should return correct ScoreResult[] for a valid response", () => {
      const schema = new ResponseSchema([
        "Output is relevant",
        "Output is concise",
      ]);
      const response = {
        assertion_1: {
          score: true,
          reason: "Matches the topic",
          confidence: 0.9,
        },
        assertion_2: {
          score: false,
          reason: "Too verbose",
          confidence: 0.8,
        },
      };

      const results = schema.parse(response);

      expect(results).toHaveLength(2);
      expect(results[0]).toEqual({
        name: "Output is relevant",
        value: 1,
        reason: "Matches the topic",
      });
      expect(results[1]).toEqual({
        name: "Output is concise",
        value: 0,
        reason: "Too verbose",
      });
    });

    it("should convert boolean true to value 1", () => {
      const schema = new ResponseSchema(["Is correct"]);
      const response = {
        assertion_1: { score: true, reason: "Yes", confidence: 1.0 },
      };

      const results = schema.parse(response);
      expect(results[0].value).toBe(1);
      expect(results[0].categoryName).toBeUndefined();
    });

    it("should convert boolean false to value 0", () => {
      const schema = new ResponseSchema(["Is correct"]);
      const response = {
        assertion_1: { score: false, reason: "No", confidence: 0.5 },
      };

      const results = schema.parse(response);
      expect(results[0].value).toBe(0);
      expect(results[0].categoryName).toBeUndefined();
    });

    it("should return scoringFailed: true for missing fields", () => {
      const schema = new ResponseSchema([
        "Output is relevant",
        "Output is concise",
      ]);
      const response = {
        assertion_1: {
          score: true,
          reason: "Good",
          confidence: 0.9,
        },
      };

      const results = schema.parse(response);

      expect(results).toHaveLength(2);
      expect(results[0].scoringFailed).toBeUndefined();
      expect(results[1]).toEqual({
        name: "Output is concise",
        value: 0,
        reason: expect.stringContaining("missing"),
        scoringFailed: true,
      });
    });

    it("should return scoringFailed: true for malformed assertion data", () => {
      const schema = new ResponseSchema(["Is correct"]);
      const response = {
        assertion_1: "not an object",
      };

      const results = schema.parse(response);

      expect(results[0]).toEqual({
        name: "Is correct",
        value: 0,
        reason: expect.stringContaining("malformed"),
        scoringFailed: true,
      });
    });

    it("should preserve assertion order in results", () => {
      const schema = new ResponseSchema(["Third", "First", "Second"]);
      const response = {
        assertion_1: { score: true, reason: "r3", confidence: 0.7 },
        assertion_2: { score: true, reason: "r1", confidence: 0.9 },
        assertion_3: { score: false, reason: "r2", confidence: 0.8 },
      };

      const results = schema.parse(response);

      expect(results[0].name).toBe("Third");
      expect(results[1].name).toBe("First");
      expect(results[2].name).toBe("Second");
    });

    it("should use assertion text as the name for each result", () => {
      const schema = new ResponseSchema([
        "The output addresses all user concerns",
      ]);
      const response = {
        assertion_1: {
          score: true,
          reason: "All concerns addressed",
          confidence: 0.95,
        },
      };

      const results = schema.parse(response);
      expect(results[0].name).toBe("The output addresses all user concerns");
    });

    it("should handle empty assertions array", () => {
      const schema = new ResponseSchema([]);
      const results = schema.parse({});
      expect(results).toEqual([]);
    });

    it("should handle many assertions and return all results in order", () => {
      const assertions = Array.from(
        { length: 10 },
        (_, i) => `Assertion number ${i + 1}`
      );
      const schema = new ResponseSchema(assertions);
      const response: Record<string, unknown> = {};
      for (let i = 1; i <= 10; i++) {
        response[`assertion_${i}`] = {
          score: i % 2 === 0,
          reason: `Reason for assertion ${i}`,
          confidence: 0.5 + i * 0.05,
        };
      }

      const results = schema.parse(response);

      expect(results).toHaveLength(10);
      for (let i = 0; i < 10; i++) {
        expect(results[i].name).toBe(`Assertion number ${i + 1}`);
        expect(results[i].value).toBe((i + 1) % 2 === 0 ? 1 : 0);
        expect(results[i].reason).toBe(`Reason for assertion ${i + 1}`);
        expect(results[i].scoringFailed).toBeUndefined();
        expect(results[i].categoryName).toBeUndefined();
      }
    });

    it("should handle assertions with special characters", () => {
      const assertion =
        'Response doesn\'t contain "quotes" or special chars: {}/\\';
      const schema = new ResponseSchema([assertion]);
      const response = {
        assertion_1: {
          score: true,
          reason: "No special chars found",
          confidence: 0.88,
        },
      };

      const results = schema.parse(response);

      expect(results).toHaveLength(1);
      expect(results[0].name).toBe(assertion);
      expect(results[0].value).toBe(1);
      expect(results[0].categoryName).toBeUndefined();
    });
  });
});
