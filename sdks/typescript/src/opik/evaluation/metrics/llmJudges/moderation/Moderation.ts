import { z } from "zod";
import { BaseMetric } from "../../BaseMetric";
import { EvaluationScoreResult } from "@/evaluation/types";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { resolveModel } from "@/evaluation/models/modelsFactory";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";
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
 * // Using custom model
 * const customMetric = new Moderation({ model: 'gpt-4-turbo' });
 *
 * // Using custom model instance
 * import { openai } from '@ai-sdk/openai';
 * const customModel = openai('gpt-4o');
 * const instanceMetric = new Moderation({ model: customModel });
 * ```
 */
export class Moderation extends BaseMetric {
  private readonly model: OpikBaseModel;
  private readonly fewShotExamples: FewShotExampleModeration[];

  /**
   * Creates a new Moderation metric.
   *
   * @param options - Configuration options
   * @param options.model - The language model to use. Can be a string (model ID), LanguageModel instance, or OpikBaseModel instance. Defaults to 'gpt-4o'.
   * @param options.name - The name of the metric. Defaults to "moderation_metric".
   * @param options.fewShotExamples - Optional few-shot examples to guide the model
   * @param options.trackMetric - Whether to track the metric. Defaults to true.
   * @param options.seed - Optional seed value for reproducible model generation
   * @param options.temperature - Optional temperature value for model generation
   */
  constructor(options?: {
    model?: SupportedModelId | LanguageModel | OpikBaseModel;
    name?: string;
    fewShotExamples?: FewShotExampleModeration[];
    trackMetric?: boolean;
    seed?: number;
    temperature?: number;
  }) {
    const name = options?.name ?? "moderation_metric";
    const trackMetric = options?.trackMetric ?? true;

    super(name, trackMetric);

    this.fewShotExamples = options?.fewShotExamples ?? [];
    this.model = this.initModel(
      options?.model,
      options?.temperature,
      options?.seed
    );
  }

  /**
   * Initializes the model instance.
   *
   * @param model - Model identifier or instance
   * @param temperature - Optional temperature setting
   * @param seed - Optional seed for reproducibility
   * @returns Initialized OpikBaseModel instance
   */
  private initModel(
    model: SupportedModelId | LanguageModel | OpikBaseModel | undefined,
    temperature?: number,
    seed?: number
  ): OpikBaseModel {
    if (model instanceof OpikBaseModel) {
      return model;
    }

    // resolveModel handles undefined, string model IDs, and LanguageModel instances
    const resolvedModel = resolveModel(model);

    // Note: The current OpikBaseModel interface doesn't support setting
    // temperature/seed after construction. This would need to be added
    // to the model interface or passed during generateString calls.
    // For now, we just return the resolved model.

    return resolvedModel;
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

    const modelOutput = await this.model.generateString(
      llmQuery,
      responseSchema
    );

    return parseModelOutput(modelOutput, this.name);
  }
}
