import { describe, it, expect } from "vitest";
import { parseModelOutput } from "@/evaluation/metrics/llmJudges/hallucination/parser";
import { MetricComputationError } from "@/evaluation/metrics/errors";

describe("Hallucination Parser", () => {
  const metricName = "hallucination_metric";

  describe("Valid JSON parsing", () => {
    it("should parse valid JSON with score and reason", () => {
      const input = JSON.stringify({
        score: 0.75,
        reason: "Some factual inconsistencies detected.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.name).toBe(metricName);
      expect(result.value).toBe(0.75);
      expect(result.reason).toBe("Some factual inconsistencies detected.");
    });

    it("should parse JSON with score at minimum boundary (0.0)", () => {
      const input = JSON.stringify({
        score: 0.0,
        reason: "No hallucination detected.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.0);
      expect(result.reason).toBe("No hallucination detected.");
    });

    it("should parse JSON with score at maximum boundary (1.0)", () => {
      const input = JSON.stringify({
        score: 1.0,
        reason: "Complete hallucination.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(1.0);
      expect(result.reason).toBe("Complete hallucination.");
    });

    it("should parse JSON with decimal score", () => {
      const input = JSON.stringify({
        score: 0.6234,
        reason: "Moderate hallucination.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.6234);
    });
  });

  describe("Reason field handling - String", () => {
    it("should handle reason as string", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: "This is a string reason.",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("This is a string reason.");
    });

    it("should handle empty reason string", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: "",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("");
    });

    it("should handle missing reason field", () => {
      const input = JSON.stringify({
        score: 0.5,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("");
    });

    it("should handle reason with special characters", () => {
      const input = JSON.stringify({
        score: 0.8,
        reason: 'Contains "quotes" and\nnewlines.',
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toContain("quotes");
      expect(result.reason).toContain("\n");
    });
  });

  describe("Reason field handling - Array", () => {
    it("should join array of reasons with space", () => {
      const input = JSON.stringify({
        score: 0.8,
        reason: ["Reason 1", "Reason 2", "Reason 3"],
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("Reason 1 Reason 2 Reason 3");
    });

    it("should handle single-item reason array", () => {
      const input = JSON.stringify({
        score: 0.6,
        reason: ["Single reason"],
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("Single reason");
    });

    it("should handle empty reason array", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: [],
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("");
    });

    it("should handle array with empty strings", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: ["", "Valid reason", ""],
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe(" Valid reason ");
    });

    it("should handle array with many items", () => {
      const reasons = Array.from({ length: 20 }, (_, i) => `Reason ${i + 1}`);
      const input = JSON.stringify({
        score: 0.7,
        reason: reasons,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBeDefined();
      expect(result.reason).toContain("Reason 1");
      expect(result.reason).toContain("Reason 20");
      expect(result.reason!.split(" ").length).toBe(40); // 20 "Reason" + 20 numbers
    });

    it("should convert non-string array items to strings", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: ["Reason", 123, true, null],
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBe("Reason 123 true null");
    });

    it("should handle array with objects", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: [{ text: "Object reason" }, "String reason"],
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toContain("[object Object]");
      expect(result.reason).toContain("String reason");
    });
  });

  describe("JSON extraction from wrapped content", () => {
    it("should extract JSON from text with prefix", () => {
      const input = 'Analysis: {"score": 0.9, "reason": "High hallucination"}';

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.9);
      expect(result.reason).toBe("High hallucination");
    });

    it("should extract JSON from text with suffix", () => {
      const input = '{"score": 0.3, "reason": "Low hallucination"} - Complete.';

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.3);
      expect(result.reason).toBe("Low hallucination");
    });

    it("should extract JSON with reason array from wrapped text", () => {
      const input = 'Result: {"score": 0.6, "reason": ["Issue 1", "Issue 2"]}';

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.6);
      expect(result.reason).toBe("Issue 1 Issue 2");
    });
  });

  describe("Error handling - Invalid scores", () => {
    it("should throw for score greater than 1.0", () => {
      const input = JSON.stringify({
        score: 1.5,
        reason: "Invalid score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for score less than 0.0", () => {
      const input = JSON.stringify({
        score: -0.2,
        reason: "Invalid score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for non-numeric score string", () => {
      const input = JSON.stringify({
        score: "high",
        reason: "Invalid score type",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for missing score field", () => {
      const input = JSON.stringify({
        reason: "Missing score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for null score", () => {
      const input = JSON.stringify({
        score: null,
        reason: "Null score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for NaN score", () => {
      const input = JSON.stringify({
        score: NaN,
        reason: "NaN score",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });
  });

  describe("Error handling - Invalid JSON", () => {
    it("should throw for completely invalid JSON", () => {
      const input = "Not JSON content";

      expect(() => parseModelOutput(input, metricName)).toThrow(
        MetricComputationError
      );
    });

    it("should throw for malformed JSON", () => {
      const input = '{"score": 0.8, "reason": "Incomplete';

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
      const input = JSON.stringify([0.5, "reason"]);

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
        score: 2.0,
        reason: "Too high",
      });

      expect(() => parseModelOutput(input, metricName)).toThrow(
        /hallucination score/i
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
        score: 0.987654321,
        reason: "Precise score",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBeCloseTo(0.987654321, 9);
    });

    it("should handle very small positive score", () => {
      const input = JSON.stringify({
        score: 0.0001,
        reason: "Minimal hallucination",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.0001);
    });

    it("should handle score very close to 1.0", () => {
      const input = JSON.stringify({
        score: 0.9999,
        reason: "Nearly complete hallucination",
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.9999);
    });

    it("should handle JSON with extra fields", () => {
      const input = JSON.stringify({
        score: 0.65,
        reason: "Some issues",
        extra_field: "ignored",
        confidence: 0.9,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.value).toBe(0.65);
      expect(result.reason).toBe("Some issues");
      expect(result).not.toHaveProperty("extra_field");
      expect(result).not.toHaveProperty("confidence");
    });

    it("should handle very long reason string", () => {
      const longReason = "A".repeat(5000);
      const input = JSON.stringify({
        score: 0.5,
        reason: longReason,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toBeDefined();
      expect(result.reason!.length).toBe(5000);
    });

    it("should handle very long reason array", () => {
      const longReasonArray = Array.from(
        { length: 100 },
        (_, i) => `Reason ${i}`
      );
      const input = JSON.stringify({
        score: 0.7,
        reason: longReasonArray,
      });

      const result = parseModelOutput(input, metricName);

      expect(result.reason).toContain("Reason 0");
      expect(result.reason).toContain("Reason 99");
    });
  });

  describe("Custom metric names", () => {
    it("should use provided metric name in result", () => {
      const customName = "custom_hallucination";
      const input = JSON.stringify({
        score: 0.5,
        reason: "Test",
      });

      const result = parseModelOutput(input, customName);

      expect(result.name).toBe(customName);
    });

    it("should handle empty metric name", () => {
      const input = JSON.stringify({
        score: 0.5,
        reason: "Test",
      });

      const result = parseModelOutput(input, "");

      expect(result.name).toBe("");
    });
  });
});
