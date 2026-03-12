import { generateText, Output, type LanguageModel } from "ai";
import type { z } from "zod";
import { OpikBaseModel, type OpikMessage } from "./OpikBaseModel";
import { ModelConfigurationError, ModelGenerationError } from "./errors";
import { logger } from "@/utils/logger";
import { detectProvider, type SupportedModelId } from "./providerDetection";
import { track } from "@/decorators/track";
import { SpanType } from "@/rest_api/api";
import { enrichSpanFromResponse } from "./usageExtractor";

export type VercelAIChatModelOptions = {
  trackGenerations?: boolean;
} & Record<string, unknown>;

/**
 * LLM model implementation using Vercel AI SDK with multi-provider support.
 *
 * This class wraps the Vercel AI SDK's `generateText` function to provide
 * a consistent interface for multiple LLM providers in the Opik evaluation system.
 *
 * Supports:
 * - Direct LanguageModel instances for maximum flexibility
 * - Typed model IDs with automatic provider detection:
 *   - OpenAI: `"gpt-5-nano"`, `"gpt-5"`, `"o1"`, etc.
 *   - Anthropic: `"claude-3-5-sonnet-latest"`, `"claude-3-opus"`, etc.
 *   - Google Gemini: `"gemini-2.0-flash"`, `"gemini-1.5-pro"`, etc.
 *
 * @example
 * ```typescript
 * // Using typed model ID
 * const model1 = new VercelAIChatModel("gpt-5-nano", {
 *   apiKey: "sk-...",
 *   organization: "org-123"
 * });
 *
 * // Using LanguageModel instance directly
 * const customModel = openai("gpt-5-nano");
 * const model2 = new VercelAIChatModel(customModel);
 * ```
 */
export class VercelAIChatModel extends OpikBaseModel {
  /**
   * The underlying AI model from the provider SDK.
   */
  private readonly model: LanguageModel;

  /**
   * Private tracked wrapper for generateText SDK method.
   */
  private _generateText: (
    params: Parameters<typeof generateText>[0]
  ) => Promise<Awaited<ReturnType<typeof generateText>>>;

  /**
   * Creates a new VercelAIChatModel instance with a LanguageModel.
   *
   * @param model - A LanguageModel instance
   */
  constructor(model: LanguageModel, options?: VercelAIChatModelOptions);

  /**
   * Creates a new VercelAIChatModel instance with a typed model ID.
   *
   * @param modelId - The model ID (e.g., 'gpt-5-nano', 'claude-3-5-sonnet-latest', 'gemini-2.0-flash')
   * @param options - Provider-specific configuration options
   */
  constructor(modelId: SupportedModelId, options?: VercelAIChatModelOptions);
  constructor(
    model: LanguageModel | SupportedModelId,
    options: VercelAIChatModelOptions = {
      trackGenerations: true,
    }
  ) {
    // Determine model name for OpikBaseModel
    const modelName = typeof model === "string" ? model : model.modelId;
    super(modelName);

    // Extract trackGenerations from options, default to true
    const { trackGenerations, ...restOptions } = options;

    try {
      // Check if it's a LanguageModel instance
      if (typeof model !== "string") {
        this.model = model;
        logger.debug(`Initialized VercelAIChatModel with custom LanguageModel`);
      } else {
        // It's a model ID string, detect provider
        this.model = detectProvider(model, restOptions);
        logger.debug(`Initialized VercelAIChatModel with model ID: ${model}`);
      }

      // Wrap Vercel AI SDK methods with track decorator if tracking is enabled
      if (trackGenerations) {
        this._generateText = track(
          {
            name: "model.generateText",
            type: SpanType.Llm,
            enrichSpan: (result) =>
              enrichSpanFromResponse(result, this.modelName),
          },
          generateText
        );
      } else {
        this._generateText = generateText;
      }
    } catch (error) {
      throw new ModelConfigurationError(
        `Failed to initialize model ${modelName}: ${error instanceof Error ? error.message : String(error)}`
      );
    }
  }

  /**
   * Generate a string response from the model.
   *
   * @param input - The prompt/input text to send to the model
   * @param responseFormat - Optional Zod schema for structured output validation
   * @param options - Optional generation parameters (temperature, maxTokens, etc.)
   * @returns The generated text response (or JSON string if responseFormat is provided)
   *
   * @throws {ModelGenerationError} If text generation fails
   *
   * @example
   * ```typescript
   * const model = new VercelAIChatModel("gpt-5-nano");
   *
   * // Simple text generation
   * const response = await model.generateString("What is 2+2?");
   * console.log(response); // "2+2 equals 4"
   *
   * // Structured output with Zod schema
   * const schema = z.object({ score: z.boolean(), reason: z.array(z.string()) });
   * const structuredResponse = await model.generateString("Evaluate...", schema);
   * console.log(structuredResponse); // '{"score": true, "reason": ["..."]}'
   * ```
   */
  async generateString(
    input: string,
    responseFormat?: z.ZodSchema,
    options?: Record<string, unknown>
  ): Promise<string> {
    try {
      let LLMResult: string;
      if (responseFormat) {
        // Use generateText with output.object() for structured output
        logger.debug(
          `Generating structured output with model ${this.modelName}, input length: ${input.length}`
        );

        const result = await this._generateText({
          model: this.model,
          prompt: input,
          output: Output.object({ schema: responseFormat }),
          ...options,
        });

        logger.debug(
          `Generated structured output with model ${this.modelName}`
        );

        LLMResult = JSON.stringify(result.output);
      } else {
        logger.debug(
          `Generating text with model ${this.modelName}, input length: ${input.length}`
        );

        const result = await this._generateText({
          model: this.model,
          prompt: input,
          ...options,
        });

        logger.debug(
          `Generated text with model ${this.modelName}, output length: ${result.text.length}`
        );

        LLMResult = result.text;
      }

      return LLMResult;
    } catch (error) {
      const errorMessage = responseFormat
        ? `Failed to generate structured output with model ${this.modelName}`
        : `Failed to generate text with model ${this.modelName}`;
      logger.error(errorMessage, { error });

      throw new ModelGenerationError(
        errorMessage,
        error instanceof Error ? error : new Error(String(error))
      );
    }
  }

  /**
   * Generate a provider-specific response object.
   *
   * Returns the full response object from Vercel AI SDK, which includes
   * text, usage information, and other metadata. When trackGenerations is enabled,
   * automatically tracks the generation with usage and metadata.
   *
   * @param messages - Array of conversation messages in Opik format
   * @param options - Optional generation parameters
   * @returns The full Vercel AI SDK GenerateTextResult
   *
   * @throws {ModelGenerationError} If generation fails
   *
   * @example
   * ```typescript
   * const model = new VercelAIChatModel("gpt-5-nano");
   * const response = await model.generateProviderResponse([
   *   { role: 'user', content: 'Hello!' }
   * ]);
   * console.log(response.text);
   * console.log(response.usage);
   * ```
   */
  async generateProviderResponse(
    messages: OpikMessage[],
    options?: Record<string, unknown>
  ): Promise<unknown> {
    try {
      logger.debug(
        `Generating provider response with model ${this.modelName}, messages count: ${messages.length}`
      );

      const result = await this._generateText({
        model: this.model,
        messages,
        ...options,
      });

      logger.debug(`Generated provider response with model ${this.modelName}`);

      return result;
    } catch (error) {
      const errorMessage = `Failed to generate provider response with model ${this.modelName}`;
      logger.error(errorMessage, { error });

      throw new ModelGenerationError(
        errorMessage,
        error instanceof Error ? error : new Error(String(error))
      );
    }
  }
}
