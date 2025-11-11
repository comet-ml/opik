import { describe, it, expect, vi, beforeEach } from "vitest";
import { Hallucination } from "@/evaluation/metrics/llmJudges/hallucination";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import * as modelsFactory from "@/evaluation/models/modelsFactory";

// Mock the modelsFactory
vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(),
}));

describe("Hallucination Metric", () => {
  let mockModel: OpikBaseModel;
  let mockGenerateString: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockGenerateString = vi.fn().mockResolvedValue(
      JSON.stringify({
        score: 0.5,
        reason: ["Test reason 1", "Test reason 2"],
      })
    );

    mockModel = {
      modelName: "test-model",
      generateString: mockGenerateString,
      generateProviderResponse: vi.fn(),
    } as unknown as OpikBaseModel;

    vi.mocked(modelsFactory.resolveModel).mockReturnValue(mockModel);
  });

  describe("Basic functionality", () => {
    it("should score with context", async () => {
      const metric = new Hallucination({
        trackMetric: false,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The capital of France is London.",
        context: ["The capital of France is Paris."],
      });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      expect(result).toHaveProperty("name", "hallucination_metric");
      expect(result).toHaveProperty("value", 0.5);
      expect(result).toHaveProperty("reason");
    });

    it("should score without context", async () => {
      const metric = new Hallucination({
        trackMetric: false,
      });

      const result = await metric.score({
        input: "What is 2+2?",
        output: "2+2 equals 4.",
      });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      expect(result).toHaveProperty("name", "hallucination_metric");
      expect(result).toHaveProperty("value", 0.5);
    });

    it("should use custom name", async () => {
      const metric = new Hallucination({
        name: "custom_hallucination",
        trackMetric: false,
      });

      const result = await metric.score({
        input: "Test input",
        output: "Test output",
      });

      expect(result.name).toBe("custom_hallucination");
    });
  });

  describe("Few-shot examples", () => {
    it("should accept few-shot examples", async () => {
      const fewShotExamples = [
        {
          input: "Who wrote Hamlet?",
          context: ["Shakespeare wrote Hamlet."],
          output: "Dickens wrote Hamlet.",
          score: 1.0,
          reason: "Incorrect attribution",
        },
      ];

      const metric = new Hallucination({
        fewShotExamples,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
        context: ["Context"],
      });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
    });
  });

  describe("Model settings", () => {
    it("should pass temperature parameter", async () => {
      const metric = new Hallucination({
        temperature: 0.3,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("temperature", 0.3);
    });

    it("should pass seed parameter", async () => {
      const metric = new Hallucination({
        seed: 42,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("seed", 42);
    });

    it("should pass maxTokens parameter", async () => {
      const metric = new Hallucination({
        maxTokens: 1000,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("maxTokens", 1000);
    });

    it("should pass modelSettings", async () => {
      const metric = new Hallucination({
        modelSettings: {
          topP: 0.9,
          presencePenalty: 0.1,
        },
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        topP: 0.9,
        presencePenalty: 0.1,
      });
    });
  });

  describe("Reason parsing", () => {
    it("should handle reason as string", async () => {
      mockGenerateString.mockResolvedValueOnce(
        JSON.stringify({
          score: 0.8,
          reason: "Single reason string",
        })
      );

      const metric = new Hallucination({
        trackMetric: false,
      });

      const result = await metric.score({
        input: "Test input",
        output: "Test output",
      });

      expect(result.reason).toBe("Single reason string");
    });

    it("should handle reason as array", async () => {
      mockGenerateString.mockResolvedValueOnce(
        JSON.stringify({
          score: 0.8,
          reason: ["Reason 1", "Reason 2", "Reason 3"],
        })
      );

      const metric = new Hallucination({
        trackMetric: false,
      });

      const result = await metric.score({
        input: "Test input",
        output: "Test output",
      });

      expect(result.reason).toBe("Reason 1 Reason 2 Reason 3");
    });
  });
});
