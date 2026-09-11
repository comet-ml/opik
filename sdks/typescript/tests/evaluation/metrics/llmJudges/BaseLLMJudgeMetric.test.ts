import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  BaseLLMJudgeMetric,
  LLMJudgeModelSettings,
} from "@/evaluation/metrics/llmJudges/BaseLLMJudgeMetric";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import type { LanguageModel } from "ai";
import * as modelsFactory from "@/evaluation/models/modelsFactory";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import { EvaluationScoreResult } from "@/evaluation/types";
import { z } from "zod";

// Mock the modelsFactory
vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(),
}));

// Concrete implementation for testing
class TestMetric extends BaseLLMJudgeMetric {
  public readonly validationSchema = z.object({});

  constructor(options?: {
    model?: SupportedModelId | LanguageModel | OpikBaseModel;
    trackMetric?: boolean;
    temperature?: number;
    seed?: number;
    maxTokens?: number;
    modelSettings?: LLMJudgeModelSettings;
  }) {
    super("test_metric", options);
  }

  // Expose protected method for testing
  public getModelOptions(): Record<string, unknown> {
    return this.buildModelOptions();
  }

  // Expose protected model for testing
  public getModel(): OpikBaseModel {
    return this.model;
  }

  async score(): Promise<EvaluationScoreResult> {
    return {
      name: this.name,
      value: 1.0,
      reason: "Test",
    };
  }
}

describe("BaseLLMJudgeMetric", () => {
  let mockModel: OpikBaseModel;

  beforeEach(() => {
    mockModel = {
      modelName: "test-model",
      generateString: vi.fn().mockResolvedValue('{"score": 1.0}'),
      generateProviderResponse: vi.fn(),
    } as unknown as OpikBaseModel;

    vi.mocked(modelsFactory.resolveModel).mockReturnValue(mockModel);
  });

  describe("Initialization", () => {
    it("should initialize with default values", () => {
      const metric = new TestMetric();

      expect(metric.name).toBe("test_metric");
      expect(metric.getModelOptions()).toEqual({});
    });

    it("should initialize with custom model", () => {
      const customModel = {
        modelName: "custom-model",
        generateString: vi.fn(),
        generateProviderResponse: vi.fn(),
      } as unknown as OpikBaseModel;

      vi.mocked(modelsFactory.resolveModel).mockReturnValue(customModel);

      new TestMetric({ model: customModel });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith(customModel, {
        trackGenerations: true,
      });
    });

    it("should initialize with model string ID", () => {
      new TestMetric({ model: "gpt-4o" });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith("gpt-4o", {
        trackGenerations: true,
      });
    });

    it("should initialize with LanguageModel instance", () => {
      const langModel = {
        modelId: "custom-model-id",
      } as LanguageModel;

      new TestMetric({ model: langModel });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith(langModel, {
        trackGenerations: true,
      });
    });

    it("should initialize with trackMetric false", () => {
      new TestMetric({ trackMetric: false });

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith(undefined, {
        trackGenerations: false,
      });
    });

    it("should initialize with trackMetric true by default", () => {
      new TestMetric();

      expect(modelsFactory.resolveModel).toHaveBeenCalledWith(undefined, {
        trackGenerations: true,
      });
    });
  });

  describe("Model options building", () => {
    it("should return empty options when no settings provided", () => {
      const metric = new TestMetric();

      expect(metric.getModelOptions()).toEqual({});
    });

    it("should include temperature in options", () => {
      const metric = new TestMetric({ temperature: 0.7 });

      expect(metric.getModelOptions()).toEqual({ temperature: 0.7 });
    });

    it("should include seed in options", () => {
      const metric = new TestMetric({ seed: 42 });

      expect(metric.getModelOptions()).toEqual({ seed: 42 });
    });

    it("should include maxTokens in options", () => {
      const metric = new TestMetric({ maxTokens: 1000 });

      expect(metric.getModelOptions()).toEqual({ maxTokens: 1000 });
    });

    it("should include all explicit parameters", () => {
      const metric = new TestMetric({
        temperature: 0.5,
        seed: 123,
        maxTokens: 500,
      });

      expect(metric.getModelOptions()).toEqual({
        temperature: 0.5,
        seed: 123,
        maxTokens: 500,
      });
    });

    it("should include modelSettings parameters", () => {
      const metric = new TestMetric({
        modelSettings: {
          topP: 0.9,
          topK: 50,
          presencePenalty: 0.1,
          frequencyPenalty: 0.2,
        },
      });

      expect(metric.getModelOptions()).toEqual({
        topP: 0.9,
        topK: 50,
        presencePenalty: 0.1,
        frequencyPenalty: 0.2,
      });
    });

    it("should merge explicit parameters with modelSettings", () => {
      const metric = new TestMetric({
        temperature: 0.6,
        seed: 99,
        modelSettings: {
          topP: 0.95,
          topK: 40,
        },
      });

      expect(metric.getModelOptions()).toEqual({
        temperature: 0.6,
        seed: 99,
        topP: 0.95,
        topK: 40,
      });
    });

    it("should prioritize explicit temperature over modelSettings temperature", () => {
      const metric = new TestMetric({
        temperature: 0.3,
        modelSettings: {
          temperature: 0.8,
          topP: 0.9,
        },
      });

      const options = metric.getModelOptions();
      expect(options.temperature).toBe(0.3);
      expect(options.topP).toBe(0.9);
    });

    it("should prioritize explicit seed over modelSettings seed", () => {
      const metric = new TestMetric({
        seed: 42,
        modelSettings: {
          seed: 999,
          topK: 50,
        },
      });

      const options = metric.getModelOptions();
      expect(options.seed).toBe(42);
      expect(options.topK).toBe(50);
    });

    it("should prioritize explicit maxTokens over modelSettings maxTokens", () => {
      const metric = new TestMetric({
        maxTokens: 500,
        modelSettings: {
          maxTokens: 2000,
          presencePenalty: 0.1,
        },
      });

      const options = metric.getModelOptions();
      expect(options.maxTokens).toBe(500);
      expect(options.presencePenalty).toBe(0.1);
    });

    it("should not include undefined values in options", () => {
      const metric = new TestMetric({
        temperature: undefined,
        seed: 42,
        maxTokens: undefined,
      });

      const options = metric.getModelOptions();
      expect(options).toEqual({ seed: 42 });
      expect(options).not.toHaveProperty("temperature");
      expect(options).not.toHaveProperty("maxTokens");
    });

    it("should handle zero temperature", () => {
      const metric = new TestMetric({ temperature: 0 });

      expect(metric.getModelOptions()).toEqual({ temperature: 0 });
    });

    it("should handle zero seed", () => {
      const metric = new TestMetric({ seed: 0 });

      expect(metric.getModelOptions()).toEqual({ seed: 0 });
    });

    it("should handle empty modelSettings object", () => {
      const metric = new TestMetric({ modelSettings: {} });

      expect(metric.getModelOptions()).toEqual({});
    });

    it("should include stopSequences from modelSettings", () => {
      const metric = new TestMetric({
        modelSettings: {
          stopSequences: ["STOP", "END"],
        },
      });

      expect(metric.getModelOptions()).toEqual({
        stopSequences: ["STOP", "END"],
      });
    });

    it("should handle custom provider-specific settings", () => {
      const metric = new TestMetric({
        modelSettings: {
          customParam1: "value1",
          customParam2: 123,
          customParam3: true,
        },
      });

      expect(metric.getModelOptions()).toEqual({
        customParam1: "value1",
        customParam2: 123,
        customParam3: true,
      });
    });

    it("should handle complex nested modelSettings", () => {
      const metric = new TestMetric({
        modelSettings: {
          topP: 0.9,
          topK: 50,
          customObject: {
            nested: "value",
          },
          customArray: [1, 2, 3],
        },
      });

      const options = metric.getModelOptions();
      expect(options.topP).toBe(0.9);
      expect(options.topK).toBe(50);
      expect(options.customObject).toEqual({ nested: "value" });
      expect(options.customArray).toEqual([1, 2, 3]);
    });
  });

  describe("Model access", () => {
    it("should provide access to initialized model", () => {
      const metric = new TestMetric();

      expect(metric.getModel()).toBe(mockModel);
      expect(metric.getModel().modelName).toBe("test-model");
    });

    it("should use resolved model from factory", () => {
      const customModel = {
        modelName: "resolved-model",
        generateString: vi.fn(),
        generateProviderResponse: vi.fn(),
      } as unknown as OpikBaseModel;

      vi.mocked(modelsFactory.resolveModel).mockReturnValue(customModel);

      const metric = new TestMetric({ model: "custom-id" });

      expect(metric.getModel()).toBe(customModel);
      expect(metric.getModel().modelName).toBe("resolved-model");
    });
  });

  describe("Inheritance behavior", () => {
    class CustomMetric extends BaseLLMJudgeMetric {
      public readonly validationSchema = z.object({});

      constructor(
        name: string,
        options?: {
          temperature?: number;
          seed?: number;
        }
      ) {
        super(name, options);
      }

      async score(): Promise<EvaluationScoreResult> {
        const options = this.buildModelOptions();
        // Use options in some way
        return {
          name: this.name,
          value: 0.5,
          reason: `Options: ${JSON.stringify(options)}`,
        };
      }
    }

    it("should allow subclasses to use custom names", () => {
      const metric = new CustomMetric("custom_name");

      expect(metric.name).toBe("custom_name");
    });

    it("should allow subclasses to access buildModelOptions", async () => {
      const metric = new CustomMetric("test", {
        temperature: 0.8,
        seed: 999,
      });

      const result = await metric.score();

      expect(result.reason).toContain("temperature");
      expect(result.reason).toContain("0.8");
      expect(result.reason).toContain("seed");
      expect(result.reason).toContain("999");
    });

    it("should maintain settings across multiple calls", async () => {
      const metric = new CustomMetric("test", { temperature: 0.5 });

      const result1 = await metric.score();
      const result2 = await metric.score();

      expect(result1.reason).toBe(result2.reason);
    });
  });

  describe("Edge cases", () => {
    it("should handle very high temperature", () => {
      const metric = new TestMetric({ temperature: 2.0 });

      expect(metric.getModelOptions()).toEqual({ temperature: 2.0 });
    });

    it("should handle very large maxTokens", () => {
      const metric = new TestMetric({ maxTokens: 100000 });

      expect(metric.getModelOptions()).toEqual({ maxTokens: 100000 });
    });

    it("should handle negative seed", () => {
      const metric = new TestMetric({ seed: -42 });

      expect(metric.getModelOptions()).toEqual({ seed: -42 });
    });

    it("should handle all parameters at once", () => {
      const metric = new TestMetric({
        temperature: 0.7,
        seed: 42,
        maxTokens: 1000,
        modelSettings: {
          topP: 0.9,
          topK: 50,
          presencePenalty: 0.1,
          frequencyPenalty: 0.2,
          stopSequences: ["STOP"],
          customParam: "value",
        },
      });

      expect(metric.getModelOptions()).toEqual({
        temperature: 0.7,
        seed: 42,
        maxTokens: 1000,
        topP: 0.9,
        topK: 50,
        presencePenalty: 0.1,
        frequencyPenalty: 0.2,
        stopSequences: ["STOP"],
        customParam: "value",
      });
    });

    it("should handle metric name with special characters", () => {
      class SpecialMetric extends BaseLLMJudgeMetric {
        public readonly validationSchema = z.object({});

        constructor() {
          super("metric-with-dashes_and_underscores");
        }
        async score(): Promise<EvaluationScoreResult> {
          return { name: this.name, value: 1.0 };
        }
      }

      const metric = new SpecialMetric();
      expect(metric.name).toBe("metric-with-dashes_and_underscores");
    });

    it("should handle empty metric name", () => {
      class EmptyNameMetric extends BaseLLMJudgeMetric {
        public readonly validationSchema = z.object({});

        constructor() {
          super("");
        }
        async score(): Promise<EvaluationScoreResult> {
          return { name: this.name, value: 1.0 };
        }
      }

      const metric = new EmptyNameMetric();
      expect(metric.name).toBe("");
    });
  });

  describe("Type safety", () => {
    it("should accept SupportedModelId as model type", () => {
      const metric = new TestMetric({ model: "gpt-4o" as SupportedModelId });

      expect(metric).toBeInstanceOf(TestMetric);
    });

    it("should accept LanguageModel as model type", () => {
      const langModel = { modelId: "test" } as LanguageModel;
      const metric = new TestMetric({ model: langModel });

      expect(metric).toBeInstanceOf(TestMetric);
    });

    it("should accept OpikBaseModel as model type", () => {
      const opikModel = {
        modelName: "test",
        generateString: vi.fn(),
        generateProviderResponse: vi.fn(),
      } as unknown as OpikBaseModel;

      const metric = new TestMetric({ model: opikModel });

      expect(metric).toBeInstanceOf(TestMetric);
    });
  });
});
