import { describe, it, expect, vi, beforeEach } from "vitest";
import { Moderation } from "@/evaluation/metrics/llmJudges/moderation";
import { Usefulness } from "@/evaluation/metrics/llmJudges/usefulness";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import * as modelsFactory from "@/evaluation/models/modelsFactory";
import type { LanguageModel } from "ai";

// Mock the modelsFactory
vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(),
}));

describe("LLM Judge Model Settings", () => {
  let mockModel: OpikBaseModel;
  let mockGenerateString: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    mockGenerateString = vi.fn().mockResolvedValue(
      JSON.stringify({
        score: 0.8,
        reason: "Test reason",
      })
    );

    mockModel = {
      modelName: "test-model",
      generateString: mockGenerateString,
      generateProviderResponse: vi.fn(),
    } as unknown as OpikBaseModel;

    vi.mocked(modelsFactory.resolveModel).mockReturnValue(mockModel);
  });

  describe("Moderation metric", () => {
    it("should pass temperature parameter to model generation", async () => {
      const metric = new Moderation({
        temperature: 0.3,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("temperature", 0.3);
    });

    it("should pass seed parameter to model generation", async () => {
      const metric = new Moderation({
        seed: 42,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("seed", 42);
    });

    it("should pass maxTokens parameter to model generation", async () => {
      const metric = new Moderation({
        maxTokens: 1000,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("maxTokens", 1000);
    });

    it("should pass multiple settings together", async () => {
      const metric = new Moderation({
        temperature: 0.5,
        seed: 123,
        maxTokens: 500,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        temperature: 0.5,
        seed: 123,
        maxTokens: 500,
      });
    });

    it("should pass modelSettings object to model generation", async () => {
      const metric = new Moderation({
        modelSettings: {
          topP: 0.9,
          topK: 50,
          presencePenalty: 0.1,
          frequencyPenalty: 0.2,
          stopSequences: ["STOP", "END"],
        },
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        topP: 0.9,
        topK: 50,
        presencePenalty: 0.1,
        frequencyPenalty: 0.2,
        stopSequences: ["STOP", "END"],
      });
    });

    it("should merge explicit params with modelSettings (explicit params take precedence)", async () => {
      const metric = new Moderation({
        temperature: 0.7,
        modelSettings: {
          temperature: 0.3, // This should be overridden
          topP: 0.9,
        },
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        temperature: 0.7, // Explicit param wins
        topP: 0.9,
      });
    });

    it("should not include undefined settings in options", async () => {
      const metric = new Moderation({
        temperature: undefined,
        seed: 42,
        maxTokens: undefined,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({ seed: 42 });
      expect(callOptions).not.toHaveProperty("temperature");
      expect(callOptions).not.toHaveProperty("maxTokens");
    });

    it("should work with string model ID", async () => {
      const metric = new Moderation({
        model: "gpt-4o",
        temperature: 0.5,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith("gpt-4o", {
        trackGenerations: false,
      });
      expect(mockGenerateString).toHaveBeenCalledTimes(1);
    });

    it("should work with LanguageModel instance", async () => {
      const mockLanguageModel = {
        modelId: "custom-model",
      } as LanguageModel;

      const metric = new Moderation({
        model: mockLanguageModel,
        temperature: 0.5,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith(
        mockLanguageModel,
        {
          trackGenerations: false,
        }
      );
      expect(mockGenerateString).toHaveBeenCalledTimes(1);
    });

    it("should work with OpikBaseModel instance", async () => {
      const customModel = {
        modelName: "custom-model",
        generateString: vi.fn().mockResolvedValue(
          JSON.stringify({
            score: 0.9,
            reason: "Custom model result",
          })
        ),
        generateProviderResponse: vi.fn(),
      } as unknown as OpikBaseModel;

      vi.mocked(modelsFactory.resolveModel).mockReturnValue(customModel);

      const metric = new Moderation({
        model: customModel,
        temperature: 0.5,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith(customModel, {
        trackGenerations: false,
      });
      expect(customModel.generateString).toHaveBeenCalledTimes(1);
    });

    it("should pass empty options object when no settings provided", async () => {
      const metric = new Moderation({
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({});
    });
  });

  describe("Usefulness metric", () => {
    it("should pass temperature parameter to model generation", async () => {
      const metric = new Usefulness({
        temperature: 0.7,
        trackMetric: false,
      });

      await metric.score({ input: "Test input", output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("temperature", 0.7);
    });

    it("should pass seed parameter to model generation", async () => {
      const metric = new Usefulness({
        seed: 999,
        trackMetric: false,
      });

      await metric.score({ input: "Test input", output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("seed", 999);
    });

    it("should pass maxTokens parameter to model generation", async () => {
      const metric = new Usefulness({
        maxTokens: 2000,
        trackMetric: false,
      });

      await metric.score({ input: "Test input", output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("maxTokens", 2000);
    });

    it("should pass multiple settings together", async () => {
      const metric = new Usefulness({
        temperature: 0.8,
        seed: 456,
        maxTokens: 1500,
        trackMetric: false,
      });

      await metric.score({ input: "Test input", output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        temperature: 0.8,
        seed: 456,
        maxTokens: 1500,
      });
    });

    it("should handle modelSettings with custom provider options", async () => {
      const metric = new Usefulness({
        modelSettings: {
          topP: 0.95,
          topK: 40,
          customParam: "custom-value",
        },
        trackMetric: false,
      });

      await metric.score({ input: "Test input", output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({
        topP: 0.95,
        topK: 40,
        customParam: "custom-value",
      });
    });
  });

  describe("Settings precedence", () => {
    it("should prioritize explicit temperature over modelSettings temperature", async () => {
      const metric = new Moderation({
        temperature: 0.2,
        modelSettings: {
          temperature: 0.8,
          seed: 100,
        },
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions.temperature).toBe(0.2);
      expect(callOptions.seed).toBe(100);
    });

    it("should prioritize explicit seed over modelSettings seed", async () => {
      const metric = new Moderation({
        seed: 42,
        modelSettings: {
          seed: 999,
          topP: 0.9,
        },
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions.seed).toBe(42);
      expect(callOptions.topP).toBe(0.9);
    });

    it("should prioritize explicit maxTokens over modelSettings maxTokens", async () => {
      const metric = new Moderation({
        maxTokens: 500,
        modelSettings: {
          maxTokens: 2000,
          topK: 50,
        },
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions.maxTokens).toBe(500);
      expect(callOptions.topK).toBe(50);
    });
  });

  describe("Edge cases", () => {
    it("should handle zero values for temperature", async () => {
      const metric = new Moderation({
        temperature: 0,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("temperature", 0);
    });

    it("should handle zero values for seed", async () => {
      const metric = new Moderation({
        seed: 0,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toHaveProperty("seed", 0);
    });

    it("should handle empty modelSettings object", async () => {
      const metric = new Moderation({
        modelSettings: {},
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions).toEqual({});
    });

    it("should handle complex stopSequences arrays", async () => {
      const metric = new Moderation({
        modelSettings: {
          stopSequences: ["STOP", "END", "FINISH", "DONE"],
        },
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const callOptions = mockGenerateString.mock.calls[0][2];
      expect(callOptions.stopSequences).toEqual([
        "STOP",
        "END",
        "FINISH",
        "DONE",
      ]);
    });
  });
});
