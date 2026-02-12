import { z } from "zod";
import {
  BaseLLMJudgeMetric,
  LLMJudgeModelSettings,
} from "../BaseLLMJudgeMetric";
import { EvaluationScoreResult } from "@/evaluation/types";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";
import { Output } from "ai";
import type { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { VercelAIChatModel } from "@/evaluation/models/VercelAIChatModel";
import { generateCoTPrompt, generateQueryPrompt } from "./template";
import { parseProviderResponse, parseModelOutputString } from "./parser";
import { GEVAL_PRESETS } from "./presets";
import { logger } from "@/utils/logger";

const validationSchema = z.object({
  output: z.string(),
});

type Input = z.infer<typeof validationSchema>;

const responseSchema = z.object({
  score: z.number(),
  reason: z.string(),
});

const MAX_COT_CACHE_SIZE = 128;
const cotCache = new Map<string, string>();

function getCachedCoT(key: string): string | undefined {
  const value = cotCache.get(key);
  if (value !== undefined) {
    cotCache.delete(key);
    cotCache.set(key, value);
  }
  return value;
}

function storeCoT(key: string, value: string): void {
  if (cotCache.has(key)) {
    cotCache.delete(key);
  }
  cotCache.set(key, value);
  while (cotCache.size > MAX_COT_CACHE_SIZE) {
    const firstKey = cotCache.keys().next().value!;
    cotCache.delete(firstKey);
  }
}

/** @internal Exposed for testing only. */
export function clearCoTCache(): void {
  cotCache.clear();
}

/**
 * Generalised evaluation metric that prompts an LLM to grade another LLM output.
 *
 * GEval builds a reusable chain-of-thought using the provided
 * `taskIntroduction` and `evaluationCriteria` prompts, then requests a
 * final score and rationale for each evaluated output.
 *
 * When logprobs are available (OpenAI models), scores are computed as a
 * weighted average of the top token probabilities for more robust scoring.
 *
 * @example
 * ```typescript
 * import { GEval } from 'opik/evaluation/metrics';
 *
 * const metric = new GEval({
 *   taskIntroduction: "You evaluate politeness of responses.",
 *   evaluationCriteria: "Score from 0 (rude) to 10 (very polite).",
 *   model: "gpt-4o",
 * });
 *
 * const result = await metric.score({ output: "Thanks so much for your help!" });
 * console.log(result.value);  // 0.0 - 1.0
 * console.log(result.reason); // Explanation
 * ```
 */
export class GEval extends BaseLLMJudgeMetric {
  private readonly taskIntroduction: string;
  private readonly evaluationCriteria: string;

  constructor(options: {
    taskIntroduction: string;
    evaluationCriteria: string;
    model?: SupportedModelId | LanguageModel | OpikBaseModel;
    name?: string;
    trackMetric?: boolean;
    temperature?: number;
    seed?: number;
    maxTokens?: number;
    modelSettings?: LLMJudgeModelSettings;
  }) {
    const name = options.name ?? "g_eval_metric";

    super(name, {
      model: options.model,
      trackMetric: options.trackMetric,
      temperature: options.temperature,
      seed: options.seed,
      maxTokens: options.maxTokens,
      modelSettings: options.modelSettings,
    });

    this.taskIntroduction = options.taskIntroduction;
    this.evaluationCriteria = options.evaluationCriteria;
  }

  public readonly validationSchema = validationSchema;

  private cotCacheKey(): string {
    return `${this.taskIntroduction}\0${this.evaluationCriteria}\0${this.model.modelName}`;
  }

  private async getChainOfThought(): Promise<string> {
    const key = this.cotCacheKey();
    const cached = getCachedCoT(key);
    if (cached !== undefined) {
      return cached;
    }

    const prompt = generateCoTPrompt(
      this.taskIntroduction,
      this.evaluationCriteria
    );

    const modelOptions = this.buildModelOptions();
    const generated = await this.model.generateString(
      prompt,
      undefined,
      modelOptions
    );

    storeCoT(key, generated);
    return generated;
  }

  /**
   * Calculates the GEval score for the given LLM output.
   *
   * Uses a two-stage process:
   * 1. Generates (or retrieves cached) chain-of-thought evaluation steps
   * 2. Evaluates the output using the CoT, returning a normalized score (0.0-1.0)
   *
   * When the model supports logprobs (e.g. OpenAI), the score is computed
   * as a weighted average of the top token probabilities for robustness.
   *
   * @param input - Object containing the `output` string to evaluate
   * @returns Score result with value (0.0-1.0) and reason
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output } = input;

    const chainOfThought = await this.getChainOfThought();

    const llmQuery = generateQueryPrompt(
      this.taskIntroduction,
      this.evaluationCriteria,
      chainOfThought,
      output
    );

    const modelOptions = this.buildModelOptions();

    try {
      const isVercelModel = this.model instanceof VercelAIChatModel;

      const providerResponse = await this.model.generateProviderResponse(
        [{ role: "user", content: llmQuery }],
        {
          ...modelOptions,
          output: Output.object({
            schema: responseSchema,
          }),
          ...(isVercelModel && {
            providerOptions: {
              openai: { logprobs: true, top_logprobs: 20 },
            },
          }),
        }
      );

      return parseProviderResponse(providerResponse, this.name);
    } catch (error) {
      logger.debug(
        `GEval failed to use logprobs for weighted scoring, falling back to text-based parsing. ` +
        `This may result in less accurate scores. Error: ${error instanceof Error ? error.message : String(error)}`
      );
      const modelOutput = await this.model.generateString(
        llmQuery,
        responseSchema,
        modelOptions
      );

      return parseModelOutputString(modelOutput, this.name);
    }
  }
}

/**
 * Pre-configured GEval variant with author-provided prompt templates.
 *
 * @example
 * ```typescript
 * import { GEvalPreset } from 'opik/evaluation/metrics';
 *
 * const metric = new GEvalPreset({ preset: "qa_relevance", model: "gpt-4o" });
 * const result = await metric.score({ output: "Paris is the capital of France." });
 * console.log(result.value);  // 0.0 - 1.0
 * ```
 */
export class GEvalPreset extends GEval {
  constructor(options: {
    preset: string;
    model?: SupportedModelId | LanguageModel | OpikBaseModel;
    name?: string;
    trackMetric?: boolean;
    temperature?: number;
    seed?: number;
    maxTokens?: number;
    modelSettings?: LLMJudgeModelSettings;
  }) {
    const definition = GEVAL_PRESETS[options.preset];

    if (!definition) {
      throw new Error(
        `Unknown GEval preset '${options.preset}'. Available presets: ${Object.keys(GEVAL_PRESETS).join(", ")}`
      );
    }

    super({
      taskIntroduction: definition.taskIntroduction,
      evaluationCriteria: definition.evaluationCriteria,
      model: options.model,
      name: options.name ?? definition.name,
      trackMetric: options.trackMetric,
      temperature: options.temperature,
      seed: options.seed,
      maxTokens: options.maxTokens,
      modelSettings: options.modelSettings,
    });
  }
}
