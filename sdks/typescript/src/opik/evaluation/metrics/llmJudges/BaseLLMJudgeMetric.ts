import { BaseMetric } from "../BaseMetric";
import { OpikBaseModel } from "@/evaluation/models/OpikBaseModel";
import { resolveModel } from "@/evaluation/models/modelsFactory";
import type { SupportedModelId } from "@/evaluation/models/providerDetection";
import type { LanguageModel } from "ai";

/**
 * Advanced model settings for LLM generation.
 * These settings are passed through to the Vercel AI SDK and provider.
 *
 * @see https://ai-sdk.dev/docs/reference/ai-sdk-core/generate-text#parameters
 */
export interface LLMJudgeModelSettings {
  /** Nucleus sampling - alternative to temperature */
  topP?: number;
  /** Top-K sampling for limiting token choices */
  topK?: number;
  /** Presence penalty to reduce repetition */
  presencePenalty?: number;
  /** Frequency penalty to reduce phrase repetition */
  frequencyPenalty?: number;
  /** Sequences that stop generation when encountered */
  stopSequences?: string[];
  /** Additional provider-specific settings */
  [key: string]: unknown;
}

/**
 * Abstract base class for all LLM judge metrics.
 *
 * This class provides common functionality for metrics that use language models
 * to evaluate content, including:
 * - Model initialization and management
 * - Model settings (temperature, seed, maxTokens, etc.)
 * - Consistent API across all LLM judge metrics
 *
 * Subclasses should implement the score() method to define their evaluation logic.
 *
 * @example
 * ```typescript
 * class MyMetric extends BaseLLMJudgeMetric {
 *   constructor(options) {
 *     super('my_metric', options);
 *   }
 *
 *   async score(input) {
 *     const options = this.buildModelOptions();
 *     const result = await this.model.generateString(prompt, schema, options);
 *     return parseResult(result);
 *   }
 * }
 * ```
 */
export abstract class BaseLLMJudgeMetric extends BaseMetric {
  /**
   * The language model instance used for evaluation
   */
  protected readonly model: OpikBaseModel;

  /**
   * Temperature setting for model generation (0 = deterministic, higher = creative)
   */
  private readonly temperature?: number;

  /**
   * Seed for reproducible model outputs
   */
  private readonly seed?: number;

  /**
   * Maximum number of tokens to generate
   */
  private readonly maxTokens?: number;

  /**
   * Advanced model settings
   */
  private readonly modelSettings?: LLMJudgeModelSettings;

  /**
   * Creates a new LLM judge metric.
   *
   * @param name - The name of the metric
   * @param options - Configuration options
   * @param options.model - The language model to use. Can be a string (model ID), LanguageModel instance, or OpikBaseModel instance. Defaults to 'gpt-5-nano'.
   * @param options.trackMetric - Whether to track the metric. Defaults to true.
   * @param options.temperature - Temperature setting (0.0-2.0). Controls randomness. Lower values make output more focused and deterministic.
   * @param options.seed - Random seed for reproducible outputs. Useful for testing and debugging.
   * @param options.maxTokens - Maximum number of tokens to generate in the response.
   * @param options.modelSettings - Advanced model settings (topP, topK, presencePenalty, etc.)
   */
  protected constructor(
    name: string,
    options?: {
      model?: SupportedModelId | LanguageModel | OpikBaseModel;
      trackMetric?: boolean;
      temperature?: number;
      seed?: number;
      maxTokens?: number;
      modelSettings?: LLMJudgeModelSettings;
    }
  ) {
    const trackMetric = options?.trackMetric ?? true;
    super(name, trackMetric);

    // Store model settings
    this.temperature = options?.temperature;
    this.seed = options?.seed;
    this.maxTokens = options?.maxTokens;
    this.modelSettings = options?.modelSettings;

    // Initialize model
    this.model = this.initModel(options?.model, {
      trackGenerations: trackMetric,
    });
  }

  /**
   * Initializes the model instance.
   *
   * @param model - Model identifier or instance
   * @returns Initialized OpikBaseModel instance
   * @private
   */
  private initModel(
    model: SupportedModelId | LanguageModel | OpikBaseModel | undefined,
    options: { trackGenerations?: boolean }
  ): OpikBaseModel {
    return resolveModel(model, options);
  }

  /**
   * Builds the options object to pass to model generation calls.
   *
   * This method merges explicit parameters (temperature, seed, maxTokens)
   * with the modelSettings object. Explicit parameters take precedence.
   *
   * Only defined values are included in the returned object.
   *
   * @returns Options object for model.generateString() calls
   * @protected
   */
  protected buildModelOptions(): Record<string, unknown> {
    return {
      ...this.modelSettings,
      ...(this.temperature !== undefined && { temperature: this.temperature }),
      ...(this.seed !== undefined && { seed: this.seed }),
      ...(this.maxTokens !== undefined && { maxTokens: this.maxTokens }),
    };
  }
}
