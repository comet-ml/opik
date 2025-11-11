import { describe, it, expect } from "vitest";
import { parseModelOutput } from "@/evaluation/metrics/llmJudges/answerRelevance/parser";
import { MetricComputationError } from "@/evaluation/metrics/errors";

describe("AnswerRelevance Parser", () => {
  const metricName = "answer_relevance_metric";

  describe("Valid JSON parsing", () => {
    it("should parse valid JSON with score and reason", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.85,
        reason: "The answer is highly relevant to the question.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.name).toBe(metricName);
      expect(result.value).toBe(0.85);
      expect(result.reason).toBe(
        "The answer is highly relevant to the question."
      );
    });

    it("should parse JSON with score at minimum boundary (0.0)", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.0,
        reason: "Completely irrelevant answer.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.0);
      expect(result.reason).toBe("Completely irrelevant answer.");
    });

    it("should parse JSON with score at maximum boundary (1.0)", () => {
      const input = JSON.stringify({
        answer_relevance_score: 1.0,
        reason: "Perfect relevance.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(1.0);
      expect(result.reason).toBe("Perfect relevance.");
    });

    it("should parse JSON with decimal score", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.7543,
        reason: "Good relevance with minor issues.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.7543);
    });

    it("should parse JSON with integer score", () => {
      const input = JSON.stringify({
        answer_relevance_score: 1,
        reason: "Perfect score as integer.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(1.0);
    });
  });

  describe("Reason field handling", () => {
    it("should handle empty reason string", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.5,
        reason: "",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("");
    });

    it("should handle missing reason field", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.5,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("");
    });

    it("should convert non-string reason to string", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.5,
        reason: 123,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("123");
    });

    it("should handle reason with special characters", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.8,
        reason: 'The answer includes "quotes" and\nnewlines.',
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toContain("quotes");
      expect(result.reason).toContain("\n");
    });

    it("should handle very long reason text", () => {
      const longReason = "A".repeat(1000);
      const input = JSON.stringify({
        answer_relevance_score: 0.75,
        reason: longReason,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe(longReason);
      expect(result.reason?.length).toBe(1000);
    });
  });

  describe("JSON extraction from wrapped content", () => {
    it("should extract JSON from text with prefix", () => {
      const input =
        'Here is my evaluation: {"answer_relevance_score": 0.9, "reason": "Great answer"}';

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.9);
      expect(result.reason).toBe("Great answer");
    });

    it("should extract JSON from text with suffix", () => {
      const input =
        '{"answer_relevance_score": 0.7, "reason": "Good answer"} - End of evaluation.';

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.7);
      expect(result.reason).toBe("Good answer");
    });

    it("should extract JSON from text with both prefix and suffix", () => {
      const input =
        'Analysis: {"answer_relevance_score": 0.85, "reason": "Relevant"} - Done.';

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.85);
      expect(result.reason).toBe("Relevant");
    });

    it("should extract JSON from multiline text", () => {
      const input = `
        Here is my analysis:
        {"answer_relevance_score": 0.88, "reason": "Well answered"}
        End of analysis.
      `;

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.88);
      expect(result.reason).toBe("Well answered");
    });
  });

  describe("Error handling - Invalid scores", () => {
    it("should throw for score greater than 1.0", () => {
      const input = JSON.stringify({
        answer_relevance_score: 1.5,
        reason: "Invalid score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for score less than 0.0", () => {
      const input = JSON.stringify({
        answer_relevance_score: -0.1,
        reason: "Invalid score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for non-numeric score string", () => {
      const input = JSON.stringify({
        answer_relevance_score: "high",
        reason: "Invalid score type",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for null score", () => {
      const input = JSON.stringify({
        answer_relevance_score: null,
        reason: "Null score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for undefined score", () => {
      const input = JSON.stringify({
        answer_relevance_score: undefined,
        reason: "Undefined score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for missing score field", () => {
      const input = JSON.stringify({
        reason: "Missing score field",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for NaN score", () => {
      const input = JSON.stringify({
        answer_relevance_score: NaN,
        reason: "NaN score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });
  });

  describe("Error handling - Invalid JSON", () => {
    it("should throw for completely invalid JSON", () => {
      const input = "This is not JSON at all";

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for malformed JSON", () => {
      const input =
        '{"answer_relevance_score": 0.8, "reason": "Unclosed string';

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for empty string", () => {
      const input = "";

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for JSON array instead of object", () => {
      const input = JSON.stringify([0.8, "reason"]);

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for empty JSON object", () => {
      const input = JSON.stringify({});

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });
  });

  describe("Error message quality", () => {
    it("should include helpful error message for invalid score", () => {
      const input = JSON.stringify({
        answer_relevance_score: 2.0,
        reason: "Too high",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        /answer relevance score/i
      );
    });

    it("should include helpful error message for parsing failure", () => {
      const input = "Invalid JSON";

      expect(() => parseModelOutput(input, metricName)).toThrow(
        /could not be parsed/i
      );
    });
  });

  describe("Edge cases", () => {
    it("should handle score with many decimal places", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.123456789,
        reason: "Precise score",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBeCloseTo(0.123456789, 9);
    });

    it("should handle very small positive score", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.0001,
        reason: "Very low relevance",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.0001);
    });

    it("should handle score very close to 1.0", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.9999,
        reason: "Nearly perfect",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.9999);
    });

    it("should handle JSON with extra fields", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.75,
        reason: "Good answer",
        extra_field: "should be ignored",
        another_field: 123,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.75);
      expect(result.reason).toBe("Good answer");
      expect(result).not.toHaveProperty("extra_field");
    });
  });

  describe("Custom metric names", () => {
    it("should use provided metric name in result", () => {
      const customName = "custom_relevance_metric";
      const input = JSON.stringify({
        answer_relevance_score: 0.5,
        reason: "Test",
      });

      const result = parseModelOutput(input, customName);

      expect(result.name).toBe(customName);
    });

    it("should handle empty metric name", () => {
      const input = JSON.stringify({
        answer_relevance_score: 0.5,
        reason: "Test",
      });

      const result = parseModelOutput(input, "");

      expect(result.name).toBe("");
    });
  });
});
