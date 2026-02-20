import { describe, it, expect, vi } from "vitest";
import { type LanguageModel } from "ai";
import { GEval } from "@/evaluation/metrics/llmJudges/gEval/GEval";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { VercelAIChatModel } from "@/evaluation/models/VercelAIChatModel";
import { openai } from "@ai-sdk/openai";
import { anthropic } from "@ai-sdk/anthropic";
import * as providerDetection from "@/evaluation/models/providerDetection";
import { logger } from "@/utils/logger";

class MockModel extends OpikBaseModel {
  private mockResponse: string;
  private shouldFailLogprobs: boolean;
  public lastProviderResponseOptions: Record<string, unknown> | undefined;

  constructor(mockResponse: string, shouldFailLogprobs = false) {
    super("mock-model");
    this.mockResponse = mockResponse;
    this.shouldFailLogprobs = shouldFailLogprobs;
  }

  async generateString(): Promise<string> {
    return this.mockResponse;
  }

  async generateProviderResponse(
    _messages: unknown,
    options?: Record<string, unknown>
  ): Promise<unknown> {
    this.lastProviderResponseOptions = options;
    if (this.shouldFailLogprobs) {
      throw new Error("Logprobs not supported");
    }
    return { text: this.mockResponse };
  }
}

describe("GEval with custom OpikBaseModel implementation", () => {
  it("should work with custom model implementation and fallback to text parsing", async () => {
    const loggerDebugSpy = vi.spyOn(logger, "debug");
    
    const mockModel = new MockModel(
      '{"score": 8, "reason": "The response correctly identifies Paris as the capital of France."}',
      true
    );
    
    const metric = new GEval({
      taskIntroduction:
        "You evaluate how well a response answers a factual question.",
      evaluationCriteria:
        "Score from 0 (incorrect) to 10 (correct and complete).",
      model: mockModel,
    });

    const result = await metric.score({
      output: "The capital of France is Paris.",
    });

    expect(result.value).toBeGreaterThanOrEqual(0.0);
    expect(result.value).toBeLessThanOrEqual(1.0);
    expect(result.value).toBeGreaterThan(0.5);
    expect(result.reason).toBeDefined();
    if (result.reason) {
      expect(typeof result.reason).toBe("string");
      expect(result.reason.length).toBeGreaterThan(0);
    }
    
    expect(loggerDebugSpy).toHaveBeenCalledWith(
      expect.stringContaining("failed to use logprobs")
    );

    loggerDebugSpy.mockRestore();
  });

  it("should normalize score from 0-10 range to 0-1 range", async () => {
    const mockModel = new MockModel(
      '{"score": 5, "reason": "Adequate response."}',
      true
    );
    
    const metric = new GEval({
      taskIntroduction: "Evaluate the response.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
    });

    const result = await metric.score({
      output: "Test output",
    });

    expect(result.value).toBeCloseTo(0.5, 2);
  });

  it("should handle score at lower boundary", async () => {
    const mockModel = new MockModel(
      '{"score": 0, "reason": "Completely incorrect."}',
      true
    );
    
    const metric = new GEval({
      taskIntroduction: "Evaluate the response.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
    });

    const result = await metric.score({
      output: "Wrong answer",
    });

    expect(result.value).toBe(0.0);
    expect(result.reason).toContain("incorrect");
  });

  it("should handle score at upper boundary", async () => {
    const mockModel = new MockModel(
      '{"score": 10, "reason": "Perfect response."}',
      true
    );
    
    const metric = new GEval({
      taskIntroduction: "Evaluate the response.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
    });

    const result = await metric.score({
      output: "Perfect answer",
    });

    expect(result.value).toBe(1.0);
    expect(result.reason).toContain("Perfect");
  });

  it("should use custom metric name", async () => {
    const mockModel = new MockModel(
      '{"score": 7, "reason": "Good response."}',
      true
    );
    
    const customName = "custom_geval_metric";
    const metric = new GEval({
      taskIntroduction: "Evaluate the response.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
      name: customName,
    });

    const result = await metric.score({
      output: "Test output",
    });

    expect(result.name).toBe(customName);
  });

  it("should use default metric name when not provided", async () => {
    const mockModel = new MockModel(
      '{"score": 7, "reason": "Good response."}',
      true
    );
    
    const metric = new GEval({
      taskIntroduction: "Evaluate the response.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
    });

    const result = await metric.score({
      output: "Test output",
    });

    expect(result.name).toBe("g_eval_metric");
  });

  it("should extract reason from response", async () => {
    const expectedReason = "The answer is factually accurate and complete.";
    const mockModel = new MockModel(
      `{"score": 9, "reason": "${expectedReason}"}`,
      true
    );
    
    const metric = new GEval({
      taskIntroduction: "Evaluate factual accuracy.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
    });

    const result = await metric.score({
      output: "Test output",
    });

    expect(result.reason).toBe(expectedReason);
  });

  it("should handle various score values correctly", async () => {
    const testScores = [0, 2, 5, 7, 10];
    
    for (const score of testScores) {
      const mockModel = new MockModel(
        `{"score": ${score}, "reason": "Test score ${score}"}`,
        true
      );
      
      const metric = new GEval({
        taskIntroduction: "Evaluate the response.",
        evaluationCriteria: "Score from 0 to 10.",
        model: mockModel,
      });

      const result = await metric.score({
        output: "Test output",
      });

      const expectedNormalized = score / 10;
      expect(result.value).toBeCloseTo(expectedNormalized, 2);
    }
  });

  it("should NOT pass providerOptions to generateProviderResponse for custom OpikBaseModel", async () => {
    const mockModel = new MockModel(
      '{"score": 7, "reason": "Good response."}',
      false
    );

    const metric = new GEval({
      taskIntroduction: "Evaluate the response.",
      evaluationCriteria: "Score from 0 to 10.",
      model: mockModel,
    });

    await metric.score({ output: "Test output" });

    expect(mockModel.lastProviderResponseOptions).toBeDefined();
    expect(mockModel.lastProviderResponseOptions).not.toHaveProperty("providerOptions");
  });
});

describe("GEval providerOptions with Vercel models", () => {
  const mockProviderResponse = {
    text: JSON.stringify({ score: 7, reason: "Good quality" }),
    providerMetadata: {},
  };

  function spyOnVercelModel(model: VercelAIChatModel) {
    vi.spyOn(model, "generateString").mockResolvedValue("CoT steps");
    const providerSpy = vi
      .spyOn(model, "generateProviderResponse")
      .mockResolvedValue(mockProviderResponse);
    return providerSpy;
  }

  it("should pass providerOptions when using openai() wrapper", async () => {
    const model = new VercelAIChatModel(openai("gpt-4o"), {
      trackGenerations: false,
    });
    const providerSpy = spyOnVercelModel(model);

    const metric = new GEval({
      taskIntroduction: "Evaluate quality.",
      evaluationCriteria: "Score from 0 to 10.",
      model,
      trackMetric: false,
    });

    await metric.score({ output: "Test output" });

    const options = providerSpy.mock.calls[0][1] as Record<string, unknown>;
    expect(options).toHaveProperty("providerOptions");
    expect(options.providerOptions).toEqual({
      openai: { logprobs: true, top_logprobs: 20 },
    });
  });

  it("should pass providerOptions when using anthropic() wrapper", async () => {
    const model = new VercelAIChatModel(anthropic("claude-haiku-4-5-20251001"), {
      trackGenerations: false,
    });
    const providerSpy = spyOnVercelModel(model);

    const metric = new GEval({
      taskIntroduction: "Evaluate quality.",
      evaluationCriteria: "Score from 0 to 10.",
      model,
      trackMetric: false,
    });

    await metric.score({ output: "Test output" });

    const options = providerSpy.mock.calls[0][1] as Record<string, unknown>;
    expect(options).toHaveProperty("providerOptions");
    expect(options.providerOptions).toEqual({
      openai: { logprobs: true, top_logprobs: 20 },
    });
  });

  it("should pass providerOptions when using string model ID (defaults to Vercel)", async () => {
    const fakeLanguageModel = {
      modelId: "gpt-4o",
      specificationVersion: "v1",
      provider: "openai",
      defaultObjectGenerationMode: "json",
    } as unknown as LanguageModel;

    vi.spyOn(providerDetection, "detectProvider").mockReturnValue(
      fakeLanguageModel
    );

    const model = new VercelAIChatModel("gpt-4o", {
      trackGenerations: false,
    });
    const providerSpy = spyOnVercelModel(model);

    vi.mocked(providerDetection.detectProvider).mockRestore();

    const metric = new GEval({
      taskIntroduction: "Evaluate quality.",
      evaluationCriteria: "Score from 0 to 10.",
      model,
      trackMetric: false,
    });

    await metric.score({ output: "Test output" });

    const options = providerSpy.mock.calls[0][1] as Record<string, unknown>;
    expect(options).toHaveProperty("providerOptions");
    expect(options.providerOptions).toEqual({
      openai: { logprobs: true, top_logprobs: 20 },
    });
  });
});
