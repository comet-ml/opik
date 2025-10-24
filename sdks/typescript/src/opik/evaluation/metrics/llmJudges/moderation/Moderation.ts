import { z } from "zod";
import {
  BaseLLMJudgeMetric,
  LLMJudgeModelSettings,
} from "../BaseLLMJudgeMetric";
import { EvaluationScoreResult } from "@/evaluation/types";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";
import type { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { FewShotExampleModeration } from "../types";
import { generateQuery } from "./template";
import { parseModelOutput } from "./parser";

const validationSchema = z.object({
  output: z.string(),
});

type Input = z.infer<typeof validationSchema>;

/**
 * Response schema for structured output from the LLM.
 */
const responseSchema = z.object({
  score: z.number(),
  reason: z.string(),
});

/**
 * Moderation metric - evaluates content safety using an LLM judge.
 *
 * This metric uses a language model to assess the moderation level of given text.
 * It returns a score between 0.0 and 1.0, where higher values indicate more
 * problematic content that violates content policies.
 *
 * The metric checks for:
 * - Violence or gore
 * - Hate speech or discrimination
 * - Sexual content or nudity
 * - Self-harm or suicide
 * - Illegal activities
 * - Personal information or privacy violations
 * - Spam or misleading content
 * - Harassment or bullying
 * - Extremism or radicalization
 * - Profanity or offensive language
 *
 * @example
 * ```typescript
 * import { Moderation } from 'opik/evaluation/metrics';
 *
 * // Using default model (gpt-4o)
 * const metric = new Moderation();
 * const result = await metric.score({ output: "Hello, how can I help you?" });
 * console.log(result.value);  // 0.0 (safe content)
 * console.log(result.reason); // Explanation
 *
 * // Using custom model with temperature and seed
 * const customMetric = new Moderation({
 *   model: 'gpt-4-turbo',
 *   temperature: 0.3,
 *   seed: 42
 * });
 *
 * // Using custom model instance
 * import { openai } from '@ai-sdk/openai';
 * const customModel = openai('gpt-4o');
 * const instanceMetric = new Moderation({ model: customModel });
 *
 * // With advanced settings
 * const advancedMetric = new Moderation({
 *   temperature: 0.5,
 *   maxTokens: 1000,
 *   modelSettings: {
 *     topP: 0.9,
 *     presencePenalty: 0.1
 *   }
 * });
 * ```
 */
export class Moderation extends BaseLLMJudgeMetric {
  private readonly fewShotExamples: FewShotExampleModeration[];

  /**
   * Creates a new Moderation metric.
   *
   * @param options - Configuration options
   * @param options.model - The language model to use. Can be a string (model ID), LanguageModel instance, or OpikBaseModel instance. Defaults to 'gpt-4o'.
   * @param options.name - The name of the metric. Defaults to "moderation_metric".
   * @param options.fewShotExamples - Optional few-shot examples to guide the model
   * @param options.trackMetric - Whether to track the metric. Defaults to true.
   * @param options.temperature - Temperature setting (0.0-2.0). Controls randomness. Lower values make output more focused and deterministic. See https://ai-sdk.dev/docs/reference/ai-sdk-core/generate-text#temperature
   * @param options.seed - Random seed for reproducible outputs. Useful for testing and debugging.
   * @param options.maxTokens - Maximum number of tokens to generate in the response.
   * @param options.modelSettings - Advanced model settings (topP, topK, presencePenalty, frequencyPenalty, stopSequences)
   */
  constructor(options?: {
    model?: SupportedModelId | LanguageModel | OpikBaseModel;
    name?: string;
    fewShotExamples?: FewShotExampleModeration[];
    trackMetric?: boolean;
    temperature?: number;
    seed?: number;
    maxTokens?: number;
    modelSettings?: LLMJudgeModelSettings;
  }) {
    const name = options?.name ?? "moderation_metric";

    super(name, {
      model: options?.model,
      trackMetric: options?.trackMetric,
      temperature: options?.temperature,
      seed: options?.seed,
      maxTokens: options?.maxTokens,
      modelSettings: options?.modelSettings,
    });

    this.fewShotExamples = options?.fewShotExamples ?? [];
  }

  public readonly validationSchema = validationSchema;

  /**
   * Calculates the moderation score for the given output.
   *
   * @param input - Input containing the output text to evaluate
   * @param input.output - The output text to be evaluated for content safety
   * @returns Score result with value (0.0-1.0) and reason
   *
   * @example
   * ```typescript
   * const metric = new Moderation();
   * const result = await metric.score({
   *   output: "This is a safe message."
   * });
   * console.log(result.value);  // 0.0
   * console.log(result.reason); // "No content policy violations detected..."
   * ```
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { output } = input;

    const llmQuery = generateQuery(output, this.fewShotExamples);

    const modelOptions = this.buildModelOptions();

    const modelOutput = await this.model.generateString(
      llmQuery,
      responseSchema,
      modelOptions
    );

    return parseModelOutput(modelOutput, this.name);
  }
}
