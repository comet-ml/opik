import { describe, it, expect } from "vitest";
import { Usefulness } from "@/evaluation/metrics/llmJudges/usefulness";
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

describe("Usefulness Metric", () => {
  describe("initialization", () => {
    it("should create metric with default name", () => {
      const mockModel = new MockModel('{"score": 1.0, "reason": "Excellent"}');
      const metric = new Usefulness({ model: mockModel });
      expect(metric.name).toBe("usefulness_metric");
    });

    it("should create metric with custom name", () => {
      const customName = "custom_usefulness";
      const mockModel = new MockModel('{"score": 1.0, "reason": "Excellent"}');
      const metric = new Usefulness({ name: customName, model: mockModel });
      expect(metric.name).toBe(customName);
    });

    it("should accept custom model instance", () => {
      const mockModel = new MockModel('{"score": 1.0, "reason": "Excellent"}');
      const metric = new Usefulness({ model: mockModel });
      expect(metric).toBeInstanceOf(Usefulness);
    });
  });

  describe("scoring with valid responses", () => {
    it("should return high score for excellent response", async () => {
      const mockModel = new MockModel(
        '{"score": 1.0, "reason": "Exceptional response that excels in all criteria: helpfulness, relevance, accuracy, depth, and creativity."}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The capital of France is Paris.",
      });

      expect(result.value).toBe(1.0);
      expect(result.reason).toContain("Exceptional");
      expect(result.name).toBe("usefulness_metric");
    });

    it("should return low score for poor response", async () => {
      const mockModel = new MockModel(
        '{"score": 0.2, "reason": "Poor response that barely addresses the question. Lacks relevance and accuracy."}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Explain quantum physics",
        output: "Something about atoms",
      });

      expect(result.value).toBe(0.2);
      expect(result.reason).toContain("Poor");
    });

    it("should return mid-range score for adequate response", async () => {
      const mockModel = new MockModel(
        '{"score": 0.6, "reason": "Good response that adequately addresses the question but could provide more depth."}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "How do I make coffee?",
        output: "Add hot water to ground coffee beans.",
      });

      expect(result.value).toBe(0.6);
      expect(result.reason).toContain("Good");
    });

    it("should handle score at boundary 0.0", async () => {
      const mockModel = new MockModel(
        '{"score": 0.0, "reason": "Completely inadequate or irrelevant response"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "What is 2+2?",
        output: "I like pizza",
      });

      expect(result.value).toBe(0.0);
    });

    it("should handle score at boundary 1.0", async () => {
      const mockModel = new MockModel(
        '{"score": 1.0, "reason": "Perfect response"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Test question",
        output: "Perfect answer",
      });

      expect(result.value).toBe(1.0);
    });
  });

  describe("input and output handling", () => {
    it("should handle both input and output parameters", async () => {
      const mockModel = new MockModel(
        '{"score": 0.8, "reason": "Well-matched response"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Specific question about TypeScript",
        output: "Detailed answer about TypeScript features",
      });

      expect(result.value).toBe(0.8);
      expect(result.name).toBe("usefulness_metric");
    });

    it("should handle empty input string", async () => {
      const mockModel = new MockModel(
        '{"score": 0.3, "reason": "Response without clear context"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "",
        output: "Some answer",
      });

      expect(result.value).toBe(0.3);
    });

    it("should handle long input and output", async () => {
      const longInput = "A".repeat(1000);
      const longOutput = "B".repeat(1000);
      const mockModel = new MockModel(
        '{"score": 0.7, "reason": "Comprehensive response"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: longInput,
        output: longOutput,
      });

      expect(result.value).toBe(0.7);
    });
  });

  describe("JSON parsing from wrapped responses", () => {
    it("should extract JSON from text with prefix", async () => {
      const mockModel = new MockModel(
        'My evaluation: {"score": 0.75, "reason": "Good quality response"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.value).toBe(0.75);
      expect(result.reason).toBe("Good quality response");
    });

    it("should extract JSON from text with suffix", async () => {
      const mockModel = new MockModel(
        '{"score": 0.85, "reason": "Excellent clarity"} - End of evaluation.'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.value).toBe(0.85);
      expect(result.reason).toBe("Excellent clarity");
    });
  });

  describe("error handling", () => {
    it("should throw MetricComputationError for invalid JSON response", async () => {
      const mockModel = new MockModel("This is not valid JSON at all");
      const metric = new Usefulness({ model: mockModel });

      await expect(
        metric.score({ input: "Question", output: "Answer" })
      ).rejects.toThrow(MetricComputationError);
    });

    it("should throw MetricComputationError for score > 1.0", async () => {
      const mockModel = new MockModel(
        '{"score": 1.8, "reason": "Invalid score"}'
      );
      const metric = new Usefulness({ model: mockModel });

      await expect(
        metric.score({ input: "Question", output: "Answer" })
      ).rejects.toThrow(MetricComputationError);
    });

    it("should throw MetricComputationError for score < 0.0", async () => {
      const mockModel = new MockModel(
        '{"score": -0.2, "reason": "Invalid score"}'
      );
      const metric = new Usefulness({ model: mockModel });

      await expect(
        metric.score({ input: "Question", output: "Answer" })
      ).rejects.toThrow(MetricComputationError);
    });

    it("should throw MetricComputationError for non-numeric score", async () => {
      const mockModel = new MockModel('{"score": "high", "reason": "Invalid"}');
      const metric = new Usefulness({ model: mockModel });

      await expect(
        metric.score({ input: "Question", output: "Answer" })
      ).rejects.toThrow(MetricComputationError);
    });

    it("should throw MetricComputationError for missing score field", async () => {
      const mockModel = new MockModel('{"reason": "Missing score field"}');
      const metric = new Usefulness({ model: mockModel });

      await expect(
        metric.score({ input: "Question", output: "Answer" })
      ).rejects.toThrow(MetricComputationError);
    });

    it("should include error message about usefulness in error", async () => {
      const mockModel = new MockModel("Invalid response");
      const metric = new Usefulness({ model: mockModel });

      try {
        await metric.score({ input: "Question", output: "Answer" });
        expect.fail("Should have thrown an error");
      } catch (error) {
        expect(error).toBeInstanceOf(MetricComputationError);
        expect((error as MetricComputationError).message).toContain(
          "usefulness"
        );
      }
    });
  });

  describe("reason extraction", () => {
    it("should extract reason from response", async () => {
      const expectedReason =
        "The response demonstrates excellent helpfulness, relevance, and accuracy";
      const mockModel = new MockModel(
        `{"score": 0.9, "reason": "${expectedReason}"}`
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.reason).toBe(expectedReason);
    });

    it("should handle empty reason string", async () => {
      const mockModel = new MockModel('{"score": 0.5, "reason": ""}');
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.reason).toBe("");
    });

    it("should handle missing reason field", async () => {
      const mockModel = new MockModel('{"score": 0.5}');
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.reason).toBe("");
    });

    it("should handle reason with special characters", async () => {
      const mockModel = new MockModel(
        '{"score": 0.8, "reason": "Response includes \\"quotes\\" and\\nline breaks"}'
      );
      const metric = new Usefulness({ model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.reason).toContain("quotes");
    });
  });

  describe("custom metric name in results", () => {
    it("should use custom name in score results", async () => {
      const customName = "my_usefulness_metric";
      const mockModel = new MockModel(
        '{"score": 0.7, "reason": "Good response"}'
      );
      const metric = new Usefulness({ name: customName, model: mockModel });

      const result = await metric.score({
        input: "Question",
        output: "Answer",
      });

      expect(result.name).toBe(customName);
    });
  });

  describe("validation schema", () => {
    it("should have correct validation schema", () => {
      const mockModel = new MockModel(
        '{"score": 0.7, "reason": "Good response"}'
      );
      const metric = new Usefulness({ model: mockModel });

      expect(metric.validationSchema).toBeDefined();
      expect(metric.validationSchema.shape).toHaveProperty("input");
      expect(metric.validationSchema.shape).toHaveProperty("output");
    });
  });

  describe("various score ranges", () => {
    it("should handle decimal scores precisely", async () => {
      const testScores = [0.0, 0.15, 0.33, 0.5, 0.67, 0.85, 1.0];

      for (const score of testScores) {
        const mockModel = new MockModel(
          `{"score": ${score}, "reason": "Test score ${score}"}`
        );
        const metric = new Usefulness({ model: mockModel });

        const result = await metric.score({
          input: "Question",
          output: "Answer",
        });

        expect(result.value).toBe(score);
      }
    });
  });
});
