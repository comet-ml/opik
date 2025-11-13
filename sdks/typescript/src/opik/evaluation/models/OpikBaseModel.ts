import type { z } from "zod";
import type {
  ModelMessage,
  SystemModelMessage,
  UserModelMessage,
  AssistantModelMessage,
  ToolModelMessage,
  UserContent,
  ToolContent,
  AssistantContent,
} from "ai";

/**
 * System message containing system instructions.
 *
 * Note: using the "system" part of the prompt is strongly preferred
 * to increase the resilience against prompt injection attacks,
 * and because not all providers support several system messages.
 */
export type OpikSystemMessage = SystemModelMessage;

/**
 * User message containing user input.
 */
export type OpikUserMessage = UserModelMessage;

/**
 * Assistant message containing model response.
 */
export type OpikAssistantMessage = AssistantModelMessage;

/**
 * Tool message containing tool call results.
 */
export type OpikToolMessage = ToolModelMessage;

/**
 * Union type of all message types.
 * This is the main type to use for message arrays in LLM conversations.
 *
 * Provider-agnostic interface that can be adapted to any LLM provider.
 */
export type OpikMessage = ModelMessage;

export type OpikMessageContent =
  | string
  | UserContent
  | AssistantContent
  | ToolContent;

/**
 * Abstract base class for all LLM model providers in Opik evaluation system.
 *
 * This interface allows different LLM providers (OpenAI, Anthropic, etc.) to be used
 * interchangeably in evaluation tasks and metrics.
 *
 * The interface is intentionally provider-agnostic and does not depend on any
 * specific SDK's types (like Vercel AI SDK, LangChain, etc.)
 *
 * @example
 * ```typescript
 * class MyCustomModel extends OpikBaseModel {
 *   constructor() {
 *     super('my-model-name');
 *   }
 *
 *   async generateString(input: string): Promise<string> {
 *     // Your implementation
 *     return 'response';
 *   }
 *
 *   async generateProviderResponse(messages: OpikMessage[]): Promise<unknown> {
 *     // Your implementation - return whatever your provider returns
 *     return {
 *       text: 'response',
 *       usage: { promptTokens: 10, completionTokens: 5 },
 *       // ... other provider-specific fields
 *     };
 *   }
 * }
 * ```
 */
export abstract class OpikBaseModel {
  /**
   * Creates a new model instance.
   *
   * @param modelName - The name of the model (e.g., 'gpt-4o', 'claude-3-opus')
   */
  constructor(public readonly modelName: string) {}

  /**
   * Simplified interface to generate a string output from the model.
   *
   * This is the primary method for simple text generation tasks.
   *
   * @param input - The input string/prompt to send to the model
   * @param responseFormat - Optional Zod schema for structured output validation
   * @param options - Optional provider-specific configuration
   * @returns The generated text response (or JSON string if responseFormat is provided)
   *
   * @example
   * ```typescript
   * const model = new VercelAIChatModel('gpt-4o');
   *
   * // Simple text generation
   * const response = await model.generateString('What is 2+2?');
   * console.log(response); // "2+2 equals 4"
   *
   * // Structured output with Zod schema
   * const schema = z.object({ score: z.boolean(), reason: z.array(z.string()) });
   * const structuredResponse = await model.generateString('Evaluate...', schema);
   * console.log(structuredResponse); // '{"score": true, "reason": ["..."]}'
   * ```
   */
  abstract generateString(
    input: string,
    responseFormat?: z.ZodSchema,
    options?: Record<string, unknown>
  ): Promise<string>;

  /**
   * Generate a provider-specific response object.
   *
   * This method provides access to the raw provider response, which may include
   * additional metadata like token usage, model info, etc.
   *
   * The return type is intentionally `unknown` to allow each provider implementation
   * to return its own native response type.
   *
   * @param messages - Array of messages in Opik format
   * @param options - Optional provider-specific configuration
   * @returns The provider's raw response object
   *
   * @example
   * ```typescript
   * const model = new VercelAIChatModel('gpt-4o');
   * const response = await model.generateProviderResponse([
   *   { role: 'user', content: 'Hello!' }
   * ]);
   * // Response type depends on the provider implementation
   * ```
   */
  abstract generateProviderResponse(
    messages: OpikMessage[],
    options?: Record<string, unknown>
  ): Promise<unknown>;
}
