import { z } from "zod";
import {
  BaseLLMJudgeMetric,
  LLMJudgeModelSettings,
} from "../BaseLLMJudgeMetric";
import { EvaluationScoreResult } from "@/evaluation/types";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";
import type { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { FewShotExampleHallucination } from "../types";
import {
  generateQueryWithContext,
  generateQueryWithoutContext,
} from "./template";
import { parseModelOutput } from "./parser";

const validationSchema = z.object({
  input: z.string(),
  output: z.string(),
  context: z.array(z.string()).optional(),
});

type Input = z.infer<typeof validationSchema>;

/**
 * Response schema for structured output from the LLM.
 */
const responseSchema = z.object({
  score: z.number(),
  reason: z.union([z.string(), z.array(z.string())]),
});

/**
 * Hallucination metric - evaluates whether an LLM's output contains hallucinations.
 *
 * This metric uses a language model to judge if the output is factual or contains
 * hallucinations based on given input and optional context. It returns a score of
 * 1.0 if hallucination is detected, and 0.0 otherwise.
 *
 * The evaluation considers:
 * - With context: Whether the output introduces information beyond the context
 * - With context: Whether the output contradicts the context
 * - Without context: Whether the output contradicts well-established facts
 * - Partial hallucinations where some information is correct but other parts are not
 * - Subtle misattributions or conflations of information
 *
 * @example
 * ```typescript
 * import { Hallucination } from 'opik/evaluation/metrics';
 *
 * // Using default model (gpt-5-nano)
 * const metric = new Hallucination();
 *
 * // With context
 * const resultWithContext = await metric.score({
 *   input: "What is the capital of France?",
 *   output: "The capital of France is London.",
 *   context: ["The capital of France is Paris."]
 * });
 * console.log(resultWithContext.value);  // 1.0 (hallucination detected)
 * console.log(resultWithContext.reason); // Explanation
 *
 * // Without context (checks against general knowledge)
 * const resultNoContext = await metric.score({
 *   input: "What is the capital of France?",
 *   output: "The capital of France is Paris."
 * });
 * console.log(resultNoContext.value);  // 0.0 (no hallucination)
 *
 * // Using custom model with few-shot examples
 * const customMetric = new Hallucination({
 *   model: 'gpt-5',
 *   temperature: 0.3,
 *   seed: 42,
 *   fewShotExamples: [
 *     {
 *       input: "Who wrote Hamlet?",
 *       context: ["Shakespeare wrote many plays including Hamlet."],
 *       output: "Charles Dickens wrote Hamlet.",
 *       score: 1.0,
 *       reason: "The output incorrectly attributes Hamlet to Charles Dickens."
 *     }
 *   ]
 * });
 *
 * // With advanced settings
 * const advancedMetric = new Hallucination({
 *   temperature: 0.5,
 *   maxTokens: 1000,
 *   modelSettings: {
 *     topP: 0.9,
 *     presencePenalty: 0.1
 *   }
 * });
 * ```
 */
export class Hallucination extends BaseLLMJudgeMetric {
  private readonly fewShotExamples: FewShotExampleHallucination[];

  /**
   * Creates a new Hallucination metric.
   *
   * @param options - Configuration options
   * @param options.model - The language model to use. Can be a string (model ID), LanguageModel instance, or OpikBaseModel instance. Defaults to 'gpt-5-nano'.
   * @param options.name - The name of the metric. Defaults to "hallucination_metric".
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
    fewShotExamples?: FewShotExampleHallucination[];
    trackMetric?: boolean;
    temperature?: number;
    seed?: number;
    maxTokens?: number;
    modelSettings?: LLMJudgeModelSettings;
  }) {
    const name = options?.name ?? "hallucination_metric";

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
   * Calculates the hallucination score for the given input-output pair.
   *
   * @param input - Input containing the question, response, and optional context
   * @param input.input - The original input/question
   * @param input.output - The LLM's output to evaluate
   * @param input.context - Optional list of context strings. If not provided, hallucinations are evaluated based on general knowledge.
   * @returns Score result with value (0.0-1.0, where 1.0 = hallucination detected, 0.0 = no hallucination) and reason
   *
   * @example
   * ```typescript
   * const metric = new Hallucination();
   *
   * // With context
   * const result = await metric.score({
   *   input: "What is the Eiffel Tower?",
   *   output: "The Eiffel Tower is located in London.",
   *   context: ["The Eiffel Tower is a landmark in Paris, France."]
   * });
   * console.log(result.value);  // High score (hallucination detected)
   *
   * // Without context
   * const result2 = await metric.score({
   *   input: "What is 2+2?",
   *   output: "2+2 equals 4."
   * });
   * console.log(result2.value);  // 0.0 (no hallucination)
   * ```
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { input: inputText, output, context } = input;

    const llmQuery = context
      ? generateQueryWithContext(
          inputText,
          output,
          context,
          this.fewShotExamples
        )
      : generateQueryWithoutContext(inputText, output, this.fewShotExamples);

    const modelOptions = this.buildModelOptions();

    const modelOutput = await this.model.generateString(
      llmQuery,
      responseSchema,
      modelOptions
    );

    return parseModelOutput(modelOutput, this.name);
  }
}
