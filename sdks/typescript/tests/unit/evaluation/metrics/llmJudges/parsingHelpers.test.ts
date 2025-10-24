import { describe, it, expect } from "vitest";
import { extractJsonContentOrRaise } from "@/evaluation/metrics/llmJudges/parsingHelpers";
import { JSONParsingError } from "@/evaluation/metrics/errors";

describe("extractJsonContentOrRaise", () => {
  describe("valid JSON parsing", () => {
    it("should parse valid JSON directly", () => {
      const validJson = '{"score": 0.8, "reason": "Good response"}';
      const result = extractJsonContentOrRaise(validJson);

      expect(result).toEqual({
        score: 0.8,
        reason: "Good response",
      });
    });

    it("should parse nested JSON objects", () => {
      const nestedJson =
        '{"score": 0.5, "metadata": {"key": "value"}, "reason": "Nested data"}';
      const result = extractJsonContentOrRaise(nestedJson);

      expect(result).toEqual({
        score: 0.5,
        metadata: { key: "value" },
        reason: "Nested data",
      });
    });

    it("should parse JSON with arrays", () => {
      const jsonWithArray =
        '{"score": 0.7, "reasons": ["point1", "point2"], "value": 123}';
      const result = extractJsonContentOrRaise(jsonWithArray);

      expect(result).toEqual({
        score: 0.7,
        reasons: ["point1", "point2"],
        value: 123,
      });
    });
  });

  describe("JSON extraction from text", () => {
    it("should extract JSON from text with prefix", () => {
      const textWithJson =
        'Here is my analysis: {"score": 0.9, "reason": "Excellent"}';
      const result = extractJsonContentOrRaise(textWithJson);

      expect(result).toEqual({
        score: 0.9,
        reason: "Excellent",
      });
    });

    it("should extract JSON from text with suffix", () => {
      const textWithJson =
        '{"score": 0.6, "reason": "Adequate"} - This is my assessment.';
      const result = extractJsonContentOrRaise(textWithJson);

      expect(result).toEqual({
        score: 0.6,
        reason: "Adequate",
      });
    });

    it("should extract JSON from text with both prefix and suffix", () => {
      const textWithJson =
        'Let me explain: {"score": 0.4, "reason": "Needs improvement"} as shown above.';
      const result = extractJsonContentOrRaise(textWithJson);

      expect(result).toEqual({
        score: 0.4,
        reason: "Needs improvement",
      });
    });

    it("should extract nested JSON from text", () => {
      const textWithJson =
        'Analysis complete: {"score": 0.75, "details": {"accuracy": 0.8, "relevance": 0.7}, "reason": "Complex nested response"}';
      const result = extractJsonContentOrRaise(textWithJson);

      expect(result).toEqual({
        score: 0.75,
        details: { accuracy: 0.8, relevance: 0.7 },
        reason: "Complex nested response",
      });
    });
  });

  describe("error handling", () => {
    it("should throw JSONParsingError for invalid JSON", () => {
      const invalidJson = "not json at all";

      expect(() => extractJsonContentOrRaise(invalidJson)).toThrow(
        JSONParsingError
      );
    });

    it("should throw JSONParsingError for malformed JSON", () => {
      const malformedJson = '{"score": 0.8, "reason": unclosed string}';

      expect(() => extractJsonContentOrRaise(malformedJson)).toThrow(
        JSONParsingError
      );
    });

    it("should throw JSONParsingError when no braces found", () => {
      const noBraces = "score: 0.8, reason: no braces";

      expect(() => extractJsonContentOrRaise(noBraces)).toThrow(
        JSONParsingError
      );
    });

    it("should throw JSONParsingError for only opening brace", () => {
      const onlyOpening = '{"score": 0.8';

      expect(() => extractJsonContentOrRaise(onlyOpening)).toThrow(
        JSONParsingError
      );
    });

    it("should throw JSONParsingError for only closing brace", () => {
      const onlyClosing = '"score": 0.8}';

      expect(() => extractJsonContentOrRaise(onlyClosing)).toThrow(
        JSONParsingError
      );
    });

    it("should include error details in JSONParsingError", () => {
      const invalidJson = "completely invalid";

      try {
        extractJsonContentOrRaise(invalidJson);
        expect.fail("Should have thrown an error");
      } catch (error) {
        expect(error).toBeInstanceOf(JSONParsingError);
        expect((error as JSONParsingError).message).toContain(
          "Failed to extract JSON"
        );
      }
    });
  });

  describe("edge cases", () => {
    it("should handle empty JSON object", () => {
      const emptyJson = "{}";
      const result = extractJsonContentOrRaise(emptyJson);

      expect(result).toEqual({});
    });

    it("should handle JSON with special characters in strings", () => {
      const specialChars =
        '{"score": 0.5, "reason": "Contains \\"quotes\\" and \\n newlines"}';
      const result = extractJsonContentOrRaise(specialChars);

      expect(result).toEqual({
        score: 0.5,
        reason: 'Contains "quotes" and \n newlines',
      });
    });

    it("should handle JSON with unicode characters", () => {
      const unicodeJson = '{"score": 0.8, "reason": "Good response! 👍 🎉"}';
      const result = extractJsonContentOrRaise(unicodeJson);

      expect(result).toEqual({
        score: 0.8,
        reason: "Good response! 👍 🎉",
      });
    });

    it("should throw error when multiple separate JSON objects exist", () => {
      const multipleObjects =
        'First: {"inner": "data"} and {"score": 0.9, "reason": "Good"}';

      // Multiple separate JSON objects cannot be parsed as a single JSON
      expect(() => extractJsonContentOrRaise(multipleObjects)).toThrow(
        JSONParsingError
      );
    });
  });
});
