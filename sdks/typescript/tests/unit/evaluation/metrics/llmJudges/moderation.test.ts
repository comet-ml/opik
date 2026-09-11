import { describe, it, expect } from "vitest";
import { Moderation } from "@/evaluation/metrics/llmJudges/moderation";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { MetricComputationError } from "@/evaluation/metrics/errors";

// Mock model for testing
class MockModel extends OpikBaseModel {
  private mockResponse: string;

  constructor(mockResponse: string) {
    super("mock-model");
    this.mockResponse = mockResponse;
  }

  async generateString(): Promise<string> {
    return this.mockResponse;
  }

  async generateProviderResponse(): Promise<unknown> {
    return { text: this.mockResponse };
  }
}

describe("Moderation Metric", () => {
  describe("initialization", () => {
    it("should create metric with default name", () => {
      const mockModel = new MockModel('{"score": 0.0, "reason": "Safe"}');
      const metric = new Moderation({ model: mockModel });
      expect(metric.name).toBe("moderation_metric");
    });

    it("should create metric with custom name", () => {
      const customName = "custom_moderation";
      const mockModel = new MockModel('{"score": 0.0, "reason": "Safe"}');
      const metric = new Moderation({ name: customName, model: mockModel });
      expect(metric.name).toBe(customName);
    });

    it("should accept custom model instance", () => {
      const mockModel = new MockModel('{"score": 0.0, "reason": "Safe"}');
      const metric = new Moderation({ model: mockModel });
      expect(metric).toBeInstanceOf(Moderation);
    });
  });

  describe("scoring with valid responses", () => {
    it("should return 0.0 for completely safe content", async () => {
      const mockModel = new MockModel(
        '{"score": 0.0, "reason": "No content policy violations detected in the text. The content appears to be completely safe."}'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({
        output: "Hello, how can I help you today?",
      });

      expect(result.value).toBe(0.0);
      expect(result.reason).toContain("safe");
      expect(result.name).toBe("moderation_metric");
    });

    it("should return high score for unsafe content", async () => {
      const mockModel = new MockModel(
        '{"score": 0.9, "reason": "Text contains severe policy violations including hate speech and violence."}'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({
        output: "Unsafe content example",
      });

      expect(result.value).toBe(0.9);
      expect(result.reason).toContain("severe");
      expect(result.name).toBe("moderation_metric");
    });

    it("should return mid-range score for borderline content", async () => {
      const mockModel = new MockModel(
        '{"score": 0.3, "reason": "Text contains mild profanity, but no severe violations. Low safety score assigned due to minimal concern."}'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({
        output: "Borderline content with mild issues",
      });

      expect(result.value).toBe(0.3);
      expect(result.reason).toContain("mild");
    });

    it("should handle score at boundary 0.0", async () => {
      const mockModel = new MockModel(
        '{"score": 0.0, "reason": "Perfectly safe"}'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Safe content" });

      expect(result.value).toBe(0.0);
    });

    it("should handle score at boundary 1.0", async () => {
      const mockModel = new MockModel(
        '{"score": 1.0, "reason": "Extremely unsafe"}'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Unsafe content" });

      expect(result.value).toBe(1.0);
    });
  });

  describe("few-shot examples", () => {
    it("should accept few-shot examples in constructor", () => {
      const examples = [
        {
          output: "Example safe text",
          score: 0.0,
          reason: "Safe content",
        },
        {
          output: "Example unsafe text",
          score: 0.8,
          reason: "Unsafe content",
        },
      ];

      const mockModel = new MockModel('{"score": 0.0, "reason": "Safe"}');
      const metric = new Moderation({
        fewShotExamples: examples,
        model: mockModel,
      });
      expect(metric).toBeInstanceOf(Moderation);
    });
  });

  describe("JSON parsing from wrapped responses", () => {
    it("should extract JSON from text with prefix", async () => {
      const mockModel = new MockModel(
        'Here is my analysis: {"score": 0.5, "reason": "Moderate issues"}'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Some content" });

      expect(result.value).toBe(0.5);
      expect(result.reason).toBe("Moderate issues");
    });

    it("should extract JSON from text with suffix", async () => {
      const mockModel = new MockModel(
        '{"score": 0.2, "reason": "Minor concerns"} - This is my assessment.'
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Some content" });

      expect(result.value).toBe(0.2);
      expect(result.reason).toBe("Minor concerns");
    });
  });

  describe("error handling", () => {
    it("should throw MetricComputationError for invalid JSON response", async () => {
      const mockModel = new MockModel("This is not valid JSON");
      const metric = new Moderation({ model: mockModel });

      await expect(metric.score({ output: "Some content" })).rejects.toThrow(
        MetricComputationError
      );
    });

    it("should throw MetricComputationError for score > 1.0", async () => {
      const mockModel = new MockModel(
        '{"score": 1.5, "reason": "Invalid score"}'
      );
      const metric = new Moderation({ model: mockModel });

      await expect(metric.score({ output: "Some content" })).rejects.toThrow(
        MetricComputationError
      );
    });

    it("should throw MetricComputationError for score < 0.0", async () => {
      const mockModel = new MockModel(
        '{"score": -0.5, "reason": "Invalid score"}'
      );
      const metric = new Moderation({ model: mockModel });

      await expect(metric.score({ output: "Some content" })).rejects.toThrow(
        MetricComputationError
      );
    });

    it("should throw MetricComputationError for non-numeric score", async () => {
      const mockModel = new MockModel(
        '{"score": "not a number", "reason": "Invalid"}'
      );
      const metric = new Moderation({ model: mockModel });

      await expect(metric.score({ output: "Some content" })).rejects.toThrow(
        MetricComputationError
      );
    });

    it("should throw MetricComputationError for missing score field", async () => {
      const mockModel = new MockModel('{"reason": "Missing score"}');
      const metric = new Moderation({ model: mockModel });

      await expect(metric.score({ output: "Some content" })).rejects.toThrow(
        MetricComputationError
      );
    });
  });

  describe("reason extraction", () => {
    it("should extract reason from response", async () => {
      const expectedReason = "Detailed explanation of the moderation decision";
      const mockModel = new MockModel(
        `{"score": 0.4, "reason": "${expectedReason}"}`
      );
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Content" });

      expect(result.reason).toBe(expectedReason);
    });

    it("should handle empty reason string", async () => {
      const mockModel = new MockModel('{"score": 0.5, "reason": ""}');
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Content" });

      expect(result.reason).toBe("");
    });

    it("should handle missing reason field", async () => {
      const mockModel = new MockModel('{"score": 0.5}');
      const metric = new Moderation({ model: mockModel });

      const result = await metric.score({ output: "Content" });

      expect(result.reason).toBe("");
    });
  });

  describe("custom metric name in results", () => {
    it("should use custom name in score results", async () => {
      const customName = "my_custom_moderation";
      const mockModel = new MockModel('{"score": 0.0, "reason": "Safe"}');
      const metric = new Moderation({ name: customName, model: mockModel });

      const result = await metric.score({ output: "Content" });

      expect(result.name).toBe(customName);
    });
  });

  describe("validation schema", () => {
    it("should have correct validation schema", () => {
      const mockModel = new MockModel('{"score": 0.0, "reason": "Safe"}');
      const metric = new Moderation({ model: mockModel });

      expect(metric.validationSchema).toBeDefined();
      expect(metric.validationSchema.shape).toHaveProperty("output");
    });
  });
});
