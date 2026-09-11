import { describe, it, expect, vi, beforeEach } from "vitest";
import { AnswerRelevance } from "@/evaluation/metrics/llmJudges/answerRelevance";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import * as modelsFactory from "@/evaluation/models/modelsFactory";
import { MetricComputationError } from "@/evaluation/metrics/errors";

// Mock the modelsFactory
vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(),
}));

describe("AnswerRelevance Metric", () => {
  let mockModel: OpikBaseModel;
  let mockGenerateString: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockGenerateString = vi.fn().mockResolvedValue(
      JSON.stringify({
        answer_relevance_score: 0.85,
        reason: "Test reason for relevance",
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
      const metric = new AnswerRelevance({
        trackMetric: false,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "The capital of France is Paris.",
        context: ["France is a country in Europe."],
      });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      expect(result).toHaveProperty("name", "answer_relevance_metric");
      expect(result).toHaveProperty("value", 0.85);
      expect(result).toHaveProperty("reason");
    });

    it("should score without context when requireContext is false", async () => {
      const metric = new AnswerRelevance({
        requireContext: false,
        trackMetric: false,
      });

      const result = await metric.score({
        input: "What is the capital of France?",
        output: "Paris is the capital of France.",
      });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      expect(result).toHaveProperty("name", "answer_relevance_metric");
      expect(result).toHaveProperty("value", 0.85);
    });

    it("should use custom name", async () => {
      const metric = new AnswerRelevance({
        name: "custom_relevance",
        trackMetric: false,
      });

      const result = await metric.score({
        input: "Test input",
        output: "Test output",
        context: ["Context"],
      });

      expect(result.name).toBe("custom_relevance");
    });
  });

  describe("Context requirement", () => {
    it("should throw error when context is required but not provided", async () => {
      const metric = new AnswerRelevance({
        requireContext: true,
        trackMetric: false,
      });

      await expect(
        metric.score({
          input: "Test input",
          output: "Test output",
        })
      ).rejects.toThrow(MetricComputationError);
    });

    it("should not throw error when requireContext is false and no context", async () => {
      const metric = new AnswerRelevance({
        requireContext: false,
        trackMetric: false,
      });

      const result = await metric.score({
        input: "Test input",
        output: "Test output",
      });

      expect(result).toHaveProperty("value");
    });

    it("should have requireContext true by default", async () => {
      const metric = new AnswerRelevance({
        trackMetric: false,
      });

      await expect(
        metric.score({
          input: "Test input",
          output: "Test output",
        })
      ).rejects.toThrow(MetricComputationError);
    });
  });

  describe("Few-shot examples", () => {
    it("should accept few-shot examples with context", async () => {
      const fewShotExamples = [
        {
          title: "High Relevance",
          input: "What is TypeScript?",
          output: "TypeScript is a typed superset of JavaScript.",
          context: ["TypeScript adds static typing to JavaScript."],
          answer_relevance_score: 0.95,
          reason: "Directly answers the question.",
        },
      ];

      const metric = new AnswerRelevance({
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

    it("should accept few-shot examples without context", async () => {
      const fewShotExamplesNoContext = [
        {
          title: "High Relevance",
          input: "What is TypeScript?",
          output: "TypeScript is a typed superset of JavaScript.",
          answer_relevance_score: 0.95,
          reason: "Directly answers the question.",
        },
      ];

      const metric = new AnswerRelevance({
        fewShotExamplesNoContext,
        requireContext: false,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
    });
  });

  describe("Model settings", () => {
    it("should pass temperature parameter", async () => {
      const metric = new AnswerRelevance({
        temperature: 0.7,
        requireContext: false,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("temperature", 0.7);
    });

    it("should pass seed parameter", async () => {
      const metric = new AnswerRelevance({
        seed: 42,
        requireContext: false,
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
      const metric = new AnswerRelevance({
        maxTokens: 2000,
        requireContext: false,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("maxTokens", 2000);
    });

    it("should pass modelSettings", async () => {
      const metric = new AnswerRelevance({
        modelSettings: {
          topP: 0.9,
          topK: 50,
        },
        requireContext: false,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        topP: 0.9,
        topK: 50,
      });
    });

    it("should merge explicit params with modelSettings", async () => {
      const metric = new AnswerRelevance({
        temperature: 0.5,
        modelSettings: {
          temperature: 0.8,
          topP: 0.9,
        },
        requireContext: false,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions.temperature).toBe(0.5);
      expect(callOptions.topP).toBe(0.9);
    });
  });

  describe("Response parsing", () => {
    it("should parse answer_relevance_score correctly", async () => {
      mockGenerateString.mockResolvedValueOnce(
        JSON.stringify({
          answer_relevance_score: 0.92,
          reason: "Excellent answer",
        })
      );

      const metric = new AnswerRelevance({
        requireContext: false,
        trackMetric: false,
      });

      const result = await metric.score({
        input: "Test input",
        output: "Test output",
      });

      expect(result.value).toBe(0.92);
      expect(result.reason).toBe("Excellent answer");
    });
  });

  describe("Template selection", () => {
    it("should use context template when context is provided", async () => {
      const metric = new AnswerRelevance({
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
        context: ["Context 1", "Context 2"],
      });

      const prompt = mockGenerateString.mock.calls[0][0];
      expect(prompt).toContain("Context:");
    });

    it("should use no-context template when context is not provided", async () => {
      const metric = new AnswerRelevance({
        requireContext: false,
        trackMetric: false,
      });

      await metric.score({
        input: "Test input",
        output: "Test output",
      });

      const prompt = mockGenerateString.mock.calls[0][0];
      expect(prompt).not.toContain("Context:");
    });
  });
});
