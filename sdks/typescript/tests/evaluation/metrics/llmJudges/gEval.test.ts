import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  GEval,
  GEvalPreset,
} from "@/evaluation/metrics/llmJudges/gEval/GEval";
import { clearCoTCache } from "@/evaluation/metrics/llmJudges/gEval/GEval";
import { GEVAL_PRESETS } from "@/evaluation/metrics/llmJudges/gEval/presets";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import * as modelsFactory from "@/evaluation/models/modelsFactory";

vi.mock("@/evaluation/models/modelsFactory", () => ({
  resolveModel: vi.fn(),
}));

describe("GEval Metric", () => {
  let mockModel: OpikBaseModel;
  let mockGenerateString: ReturnType<typeof vi.fn>;
  let mockGenerateProviderResponse: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    clearCoTCache();

    mockGenerateString = vi.fn().mockResolvedValue(
      "Step 1: Evaluate clarity. Step 2: Check accuracy."
    );

    mockGenerateProviderResponse = vi.fn().mockResolvedValue({
      text: JSON.stringify({ score: 7, reason: "Good quality" }),
      providerMetadata: {
        openai: {
          logprobs: {
            content: [
              { token: '{"', logprob: -0.01 },
              { token: "score", logprob: -0.01 },
              { token: '":', logprob: -0.01 },
              {
                token: "7",
                logprob: -0.3,
                top_logprobs: [
                  { token: "7", logprob: -0.3 },
                  { token: "8", logprob: -1.0 },
                ],
              },
            ],
          },
        },
      },
    });

    mockModel = {
      modelName: "test-model",
      generateString: mockGenerateString,
      generateProviderResponse: mockGenerateProviderResponse,
    } as unknown as OpikBaseModel;

    vi.mocked(modelsFactory.resolveModel).mockReturnValue(mockModel);
  });

  describe("Constructor", () => {
    it("should use default metric name", () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      expect(metric.name).toBe("g_eval_metric");
    });

    it("should use custom metric name", () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        name: "custom_g_eval",
        trackMetric: false,
      });

      expect(metric.name).toBe("custom_g_eval");
    });
  });

  describe("Validation schema", () => {
    it("should accept valid input with output", () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      const result = metric.validationSchema.safeParse({
        output: "Test output",
      });

      expect(result.success).toBe(true);
    });

    it("should reject input without output", () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      const result = metric.validationSchema.safeParse({});

      expect(result.success).toBe(false);
    });

    it("should reject non-string output", () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      const result = metric.validationSchema.safeParse({ output: 123 });

      expect(result.success).toBe(false);
    });
  });

  describe("Score method", () => {
    it("should return a valid score result", async () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      const result = await metric.score({ output: "Test output" });

      expect(result).toHaveProperty("name", "g_eval_metric");
      expect(result).toHaveProperty("value");
      expect(result.value).toBeGreaterThanOrEqual(0);
      expect(result.value).toBeLessThanOrEqual(1);
      expect(result).toHaveProperty("reason");
    });

    it("should call generateString for CoT generation", async () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const cotPrompt = mockGenerateString.mock.calls[0][0] as string;
      expect(cotPrompt).toContain("Evaluate quality.");
      expect(cotPrompt).toContain("Score from 0 to 10.");
    });

    it("should call generateProviderResponse for scoring", async () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateProviderResponse).toHaveBeenCalledTimes(1);
      const messages = mockGenerateProviderResponse.mock.calls[0][0];
      expect(messages).toHaveLength(1);
      expect(messages[0].role).toBe("user");
      expect(messages[0].content).toContain("Test output");
    });

    it("should pass providerOptions for logprobs", async () => {
      const metric = new GEval({
        taskIntroduction: "Evaluate quality.",
        evaluationCriteria: "Score from 0 to 10.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const options = mockGenerateProviderResponse.mock.calls[0][1];
      expect(options).toHaveProperty("providerOptions");
      expect(options.providerOptions).toEqual({
        openai: { logprobs: true, top_logprobs: 20 },
      });
    });

    it("should fall back to generateString when generateProviderResponse fails", async () => {
      mockGenerateProviderResponse.mockRejectedValueOnce(
        new Error("Provider error")
      );
      mockGenerateString
        .mockResolvedValueOnce("CoT steps")
        .mockResolvedValueOnce(
          JSON.stringify({ score: 6, reason: "Fallback" })
        );

      const metric = new GEval({
        taskIntroduction: "Evaluate fallback.",
        evaluationCriteria: "Score fallback.",
        trackMetric: false,
      });

      const result = await metric.score({ output: "Test output" });

      expect(result.value).toBeCloseTo(0.6);
      expect(result.reason).toBe("Fallback");
      expect(mockGenerateString).toHaveBeenCalledTimes(2);
    });
  });

  describe("CoT caching", () => {
    it("should cache CoT and not regenerate on second call", async () => {
      const metric = new GEval({
        taskIntroduction: "Cache test intro.",
        evaluationCriteria: "Cache test criteria.",
        trackMetric: false,
      });

      await metric.score({ output: "First output" });
      await metric.score({ output: "Second output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      expect(mockGenerateProviderResponse).toHaveBeenCalledTimes(2);
    });
  });

  describe("Model settings", () => {
    it("should pass temperature to CoT generation", async () => {
      const metric = new GEval({
        taskIntroduction: "Temperature test.",
        evaluationCriteria: "Temp criteria.",
        temperature: 0.3,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const cotOptions = mockGenerateString.mock.calls[0][2];
      expect(cotOptions).toHaveProperty("temperature", 0.3);
    });

    it("should pass seed to generation", async () => {
      const metric = new GEval({
        taskIntroduction: "Seed test.",
        evaluationCriteria: "Seed criteria.",
        seed: 42,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const cotOptions = mockGenerateString.mock.calls[0][2];
      expect(cotOptions).toHaveProperty("seed", 42);
    });

    it("should pass maxTokens to generation", async () => {
      const metric = new GEval({
        taskIntroduction: "MaxTokens test.",
        evaluationCriteria: "MaxTokens criteria.",
        maxTokens: 500,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
      const cotOptions = mockGenerateString.mock.calls[0][2];
      expect(cotOptions).toHaveProperty("maxTokens", 500);
    });

    it("should not include seed when not provided", async () => {
      const metric = new GEval({
        taskIntroduction: "No seed test.",
        evaluationCriteria: "No seed criteria.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const cotOptions = mockGenerateString.mock.calls[0][2] as Record<
        string,
        unknown
      >;
      expect(cotOptions.seed).toBeUndefined();
    });

    it("should pass temperature and seed to scoring call", async () => {
      const metric = new GEval({
        taskIntroduction: "Settings scoring test.",
        evaluationCriteria: "Settings criteria.",
        temperature: 0.5,
        seed: 99,
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const scoringOptions = mockGenerateProviderResponse.mock.calls[0][1];
      expect(scoringOptions).toHaveProperty("temperature", 0.5);
      expect(scoringOptions).toHaveProperty("seed", 99);
    });
  });

  describe("CoT caching - separate keys", () => {
    it("should generate separate CoT for different taskIntroduction", async () => {
      const metric1 = new GEval({
        taskIntroduction: "Task A.",
        evaluationCriteria: "Criteria.",
        trackMetric: false,
      });
      const metric2 = new GEval({
        taskIntroduction: "Task B.",
        evaluationCriteria: "Criteria.",
        trackMetric: false,
      });

      await metric1.score({ output: "Output 1" });
      await metric2.score({ output: "Output 2" });

      expect(mockGenerateString).toHaveBeenCalledTimes(2);
    });

    it("should generate separate CoT for different evaluationCriteria", async () => {
      const metric1 = new GEval({
        taskIntroduction: "Task.",
        evaluationCriteria: "Criteria A.",
        trackMetric: false,
      });
      const metric2 = new GEval({
        taskIntroduction: "Task.",
        evaluationCriteria: "Criteria B.",
        trackMetric: false,
      });

      await metric1.score({ output: "Output 1" });
      await metric2.score({ output: "Output 2" });

      expect(mockGenerateString).toHaveBeenCalledTimes(2);
    });

    it("should share CoT for identical config", async () => {
      const metric1 = new GEval({
        taskIntroduction: "Same task.",
        evaluationCriteria: "Same criteria.",
        trackMetric: false,
      });
      const metric2 = new GEval({
        taskIntroduction: "Same task.",
        evaluationCriteria: "Same criteria.",
        trackMetric: false,
      });

      await metric1.score({ output: "Output 1" });
      await metric2.score({ output: "Output 2" });

      expect(mockGenerateString).toHaveBeenCalledTimes(1);
    });
  });

  describe("Scoring query content", () => {
    it("should include CoT in the scoring query message", async () => {
      const cotContent = "Step 1: Evaluate clarity. Step 2: Check accuracy.";
      mockGenerateString.mockResolvedValueOnce(cotContent);

      const metric = new GEval({
        taskIntroduction: "Query content test.",
        evaluationCriteria: "Query criteria.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const messages = mockGenerateProviderResponse.mock.calls[0][0];
      expect(messages[0].content).toContain(cotContent);
    });

    it("should include the output in the scoring query message", async () => {
      const metric = new GEval({
        taskIntroduction: "Query output test.",
        evaluationCriteria: "Query criteria.",
        trackMetric: false,
      });

      await metric.score({ output: "My specific output text" });

      const messages = mockGenerateProviderResponse.mock.calls[0][0];
      expect(messages[0].content).toContain("My specific output text");
    });

    it("should include task introduction in the scoring query", async () => {
      const metric = new GEval({
        taskIntroduction: "Unique task intro for test.",
        evaluationCriteria: "Some criteria.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const messages = mockGenerateProviderResponse.mock.calls[0][0];
      expect(messages[0].content).toContain("Unique task intro for test.");
    });

    it("should include evaluation criteria in the scoring query", async () => {
      const metric = new GEval({
        taskIntroduction: "Some task.",
        evaluationCriteria: "Unique criteria for test.",
        trackMetric: false,
      });

      await metric.score({ output: "Test output" });

      const messages = mockGenerateProviderResponse.mock.calls[0][0];
      expect(messages[0].content).toContain("Unique criteria for test.");
    });
  });
});

describe("GEvalPreset", () => {
  let mockModel: OpikBaseModel;

  beforeEach(() => {
    clearCoTCache();

    mockModel = {
      modelName: "test-model",
      generateString: vi.fn().mockResolvedValue("CoT steps"),
      generateProviderResponse: vi.fn().mockResolvedValue({
        text: JSON.stringify({ score: 5, reason: "Average" }),
        providerMetadata: {},
      }),
    } as unknown as OpikBaseModel;

    vi.mocked(modelsFactory.resolveModel).mockReturnValue(mockModel);
  });

  describe("Valid presets", () => {
    it("should create metric from qa_relevance preset", () => {
      const metric = new GEvalPreset({
        preset: "qa_relevance",
        trackMetric: false,
      });

      expect(metric.name).toBe("g_eval_qa_relevance_metric");
    });

    it("should create metric from summarization_consistency preset", () => {
      const metric = new GEvalPreset({
        preset: "summarization_consistency",
        trackMetric: false,
      });

      expect(metric.name).toBe("g_eval_summarization_consistency_metric");
    });

    it("should allow custom name override", () => {
      const metric = new GEvalPreset({
        preset: "qa_relevance",
        name: "my_custom_metric",
        trackMetric: false,
      });

      expect(metric.name).toBe("my_custom_metric");
    });
  });

  describe("All presets instantiate correctly", () => {
    const allPresetNames = Object.keys(GEVAL_PRESETS);

    it.each(allPresetNames)(
      "should create metric from '%s' preset",
      (presetName) => {
        const metric = new GEvalPreset({
          preset: presetName,
          trackMetric: false,
        });

        expect(metric.name).toBe(GEVAL_PRESETS[presetName].name);
      }
    );

    it("should have exactly 13 presets defined", () => {
      expect(allPresetNames).toHaveLength(13);
    });

    it("should include all expected preset names", () => {
      const expectedPresets = [
        "summarization_consistency",
        "summarization_coherence",
        "dialogue_helpfulness",
        "qa_relevance",
        "bias_demographic",
        "bias_political",
        "bias_gender",
        "bias_religion",
        "bias_regional",
        "agent_tool_correctness",
        "agent_task_completion",
        "prompt_uncertainty",
        "compliance_regulated_truthfulness",
      ];

      for (const name of expectedPresets) {
        expect(allPresetNames).toContain(name);
      }
    });
  });

  describe("Preset definitions match", () => {
    it("should use taskIntroduction from preset definition", () => {
      const presetName = "summarization_consistency";
      const metric = new GEvalPreset({
        preset: presetName,
        trackMetric: false,
      });

      expect((metric as unknown as { taskIntroduction: string }).taskIntroduction).toBe(
        GEVAL_PRESETS[presetName].taskIntroduction
      );
    });

    it("should use evaluationCriteria from preset definition", () => {
      const presetName = "summarization_consistency";
      const metric = new GEvalPreset({
        preset: presetName,
        trackMetric: false,
      });

      expect(
        (metric as unknown as { evaluationCriteria: string }).evaluationCriteria
      ).toBe(GEVAL_PRESETS[presetName].evaluationCriteria);
    });
  });

  describe("Invalid presets", () => {
    it("should throw for unknown preset", () => {
      expect(
        () =>
          new GEvalPreset({
            preset: "nonexistent_preset",
            trackMetric: false,
          })
      ).toThrow(/Unknown GEval preset/);
    });

    it("should include available presets in error message", () => {
      expect(
        () =>
          new GEvalPreset({
            preset: "invalid",
            trackMetric: false,
          })
      ).toThrow(/Available presets/);
    });

    it("should throw for empty string preset", () => {
      expect(
        () =>
          new GEvalPreset({
            preset: "",
            trackMetric: false,
          })
      ).toThrow(/Unknown GEval preset/);
    });
  });

  describe("Preset with model settings", () => {
    it("should pass seed through preset constructor", () => {
      const metric = new GEvalPreset({
        preset: "qa_relevance",
        seed: 42,
        trackMetric: false,
      });

      expect(metric).toBeDefined();
      expect(metric.name).toBe("g_eval_qa_relevance_metric");
    });

    it("should pass temperature through preset constructor", () => {
      const metric = new GEvalPreset({
        preset: "qa_relevance",
        temperature: 0.3,
        trackMetric: false,
      });

      expect(metric).toBeDefined();
    });

    it("should pass maxTokens through preset constructor", () => {
      const metric = new GEvalPreset({
        preset: "qa_relevance",
        maxTokens: 500,
        trackMetric: false,
      });

      expect(metric).toBeDefined();
    });
  });
});
