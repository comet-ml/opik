import { z } from "zod";
import {
  BaseLLMJudgeMetric,
  LLMJudgeModelSettings,
} from "../BaseLLMJudgeMetric";
import { EvaluationScoreResult } from "@/evaluation/types";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";
import type { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import {
  FewShotExampleAnswerRelevanceWithContext,
  FewShotExampleAnswerRelevanceNoContext,
} from "../types";
import {
  generateQueryWithContext,
  generateQueryNoContext,
  FEW_SHOT_EXAMPLES_WITH_CONTEXT,
  FEW_SHOT_EXAMPLES_NO_CONTEXT,
} from "./template";
import { parseModelOutput } from "./parser";
import { MetricComputationError } from "../../errors";

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
  answer_relevance_score: z.number(),
  reason: z.string(),
});

/**
 * AnswerRelevance metric - evaluates the relevance of an answer to a given input.
 *
 * This metric uses a language model to assess how well the given output (answer)
 * addresses the provided input (question) within the given context. It returns a score
 * between 0.0 and 1.0, where higher values indicate better answer relevance.
 *
 * The evaluation considers:
 * - How well the answer addresses the specific question
 * - Whether the answer provides relevant information
 * - If any extraneous or off-topic information decreases relevance
 * - With context: How well the answer aligns with the provided context
 * - Without context: How well the answer addresses the input directly
 *
 * @example
 * ```typescript
 * import { AnswerRelevance } from 'opik/evaluation/metrics';
 *
 * // Using default model (gpt-4o)
 * const metric = new AnswerRelevance();
 *
 * // With context (default behavior)
 * const resultWithContext = await metric.score({
 *   input: "What's the capital of France?",
 *   output: "The capital of France is Paris.",
 *   context: ["France is a country in Europe."]
 * });
 * console.log(resultWithContext.value);  // e.g., 0.9
 * console.log(resultWithContext.reason); // Explanation
 *
 * // Without context (requires requireContext: false)
 * const metricNoContext = new AnswerRelevance({ requireContext: false });
 * const resultNoContext = await metricNoContext.score({
 *   input: "What's the capital of France?",
 *   output: "The capital of France is Paris."
 * });
 * console.log(resultNoContext.value);  // e.g., 0.95
 *
 * // Using custom model with few-shot examples
 * const customMetric = new AnswerRelevance({
 *   model: 'gpt-4-turbo',
 *   temperature: 0.3,
 *   seed: 42,
 *   fewShotExamples: [
 *     {
 *       title: "Perfect Answer",
 *       input: "What is TypeScript?",
 *       output: "TypeScript is a typed superset of JavaScript.",
 *       context: ["TypeScript adds static typing to JavaScript."],
 *       answer_relevance_score: 1.0,
 *       reason: "Perfect, direct answer."
 *     }
 *   ]
 * });
 *
 * // With advanced settings
 * const advancedMetric = new AnswerRelevance({
 *   temperature: 0.5,
 *   maxTokens: 1000,
 *   requireContext: false,
 *   modelSettings: {
 *     topP: 0.9,
 *     presencePenalty: 0.1
 *   }
 * });
 * ```
 */
export class AnswerRelevance extends BaseLLMJudgeMetric {
  private readonly fewShotExamplesWithContext: FewShotExampleAnswerRelevanceWithContext[];
  private readonly fewShotExamplesNoContext: FewShotExampleAnswerRelevanceNoContext[];
  private readonly requireContext: boolean;

  /**
   * Creates a new AnswerRelevance metric.
   *
   * @param options - Configuration options
   * @param options.model - The language model to use. Can be a string (model ID), LanguageModel instance, or OpikBaseModel instance. Defaults to 'gpt-4o'.
   * @param options.name - The name of the metric. Defaults to "answer_relevance_metric".
   * @param options.fewShotExamples - Optional few-shot examples with context to guide the model. If not provided, default examples will be used.
   * @param options.fewShotExamplesNoContext - Optional few-shot examples without context for no-context mode. If not provided, default examples will be used.
   * @param options.requireContext - If set to false, execution in no-context mode is allowed. Defaults to true.
   * @param options.trackMetric - Whether to track the metric. Defaults to true.
   * @param options.temperature - Temperature setting (0.0-2.0). Controls randomness. Lower values make output more focused and deterministic. See https://ai-sdk.dev/docs/reference/ai-sdk-core/generate-text#temperature
   * @param options.seed - Random seed for reproducible outputs. Useful for testing and debugging.
   * @param options.maxTokens - Maximum number of tokens to generate in the response.
   * @param options.modelSettings - Advanced model settings (topP, topK, presencePenalty, frequencyPenalty, stopSequences)
   */
  constructor(options?: {
    model?: SupportedModelId | LanguageModel | OpikBaseModel;
    name?: string;
    fewShotExamples?: FewShotExampleAnswerRelevanceWithContext[];
    fewShotExamplesNoContext?: FewShotExampleAnswerRelevanceNoContext[];
    requireContext?: boolean;
    trackMetric?: boolean;
    temperature?: number;
    seed?: number;
    maxTokens?: number;
    modelSettings?: LLMJudgeModelSettings;
  }) {
    const name = options?.name ?? "answer_relevance_metric";

    super(name, {
      model: options?.model,
      trackMetric: options?.trackMetric,
      temperature: options?.temperature,
      seed: options?.seed,
      maxTokens: options?.maxTokens,
      modelSettings: options?.modelSettings,
    });

    this.fewShotExamplesWithContext =
      options?.fewShotExamples ?? FEW_SHOT_EXAMPLES_WITH_CONTEXT;
    this.fewShotExamplesNoContext =
      options?.fewShotExamplesNoContext ?? FEW_SHOT_EXAMPLES_NO_CONTEXT;
    this.requireContext = options?.requireContext ?? true;
  }

  public readonly validationSchema = validationSchema;

  /**
   * Calculates the answer relevance score for the given input-output pair.
   *
   * @param input - Input containing the question, response, and optional context
   * @param input.input - The input text (question) to be evaluated
   * @param input.output - The output text (answer) to be evaluated
   * @param input.context - Optional list of context strings relevant to the input. If no context is given and requireContext is true, an error will be thrown.
   * @returns Score result with value (0.0-1.0) and reason
   *
   * @example
   * ```typescript
   * const metric = new AnswerRelevance();
   *
   * // With context
   * const result = await metric.score({
   *   input: "How do I install Node.js?",
   *   output: "You can download Node.js from the official website and run the installer.",
   *   context: ["Node.js is a JavaScript runtime built on Chrome's V8 engine."]
   * });
   * console.log(result.value);  // e.g., 0.8
   * console.log(result.reason); // "The answer provides clear installation steps..."
   *
   * // Without context (if requireContext: false)
   * const metricNoContext = new AnswerRelevance({ requireContext: false });
   * const result2 = await metricNoContext.score({
   *   input: "What is the capital of France?",
   *   output: "Paris is the capital of France."
   * });
   * console.log(result2.value);  // e.g., 0.95
   * ```
   */
  async score(input: Input): Promise<EvaluationScoreResult> {
    const { input: inputText, output, context } = input;

    // Check if context is required but not provided
    if (!context && this.requireContext) {
      throw new MetricComputationError(
        `${this.name} requires context by default. If you want to allow execution in no-context mode, enable it via 'new AnswerRelevance({ requireContext: false })'`
      );
    }

    const llmQuery = context
      ? generateQueryWithContext(
          inputText,
          output,
          context,
          this.fewShotExamplesWithContext
        )
      : generateQueryNoContext(
          inputText,
          output,
          this.fewShotExamplesNoContext
        );

    const modelOptions = this.buildModelOptions();

    const modelOutput = await this.model.generateString(
      llmQuery,
      responseSchema,
      modelOptions
    );

    return parseModelOutput(modelOutput, this.name);
  }
}
