import type {
  LanguageModelUsage,
  LanguageModelResponseMetadata,
  GenerateTextResult,
} from "ai";
import type { Span } from "@/rest_api/api";

/**
 * Utility functions for extracting usage, model, provider, and metadata
 * from Vercel AI SDK responses.
 */

/**
 * Constants for token field mappings between Vercel AI SDK and Opik formats
 */
const TOKEN_FIELD_MAPPINGS = {
  inputTokens: "prompt_tokens",
  outputTokens: "completion_tokens",
  totalTokens: "total_tokens",
} as const;

/**
 * Usage information extracted from model responses
 * Picks relevant fields from Span type
 */
export type UsageInfo = Pick<
  Span,
  "usage" | "model" | "provider" | "metadata" | "output"
>;

/**
 * Vercel AI SDK response type
 * GenerateTextResult requires TOOLS and OUTPUT type parameters
 * In AI SDK v6, structured outputs use GenerateTextResult with Output.object()
 * Using never for OUTPUT since we handle both generateText and generateObject responses generically
 */
type VercelAIResponse = GenerateTextResult<never, never>;

/**
 * Type guard to check if a value is a valid Vercel AI SDK response
 */
function isVercelAIResponse(value: unknown): value is VercelAIResponse {
  if (!value || typeof value !== "object") {
    return false;
  }

  const response = value as Record<string, unknown>;

  // Check for presence of required fields from either generateText or generateObject
  const hasUsage = "usage" in response;
  const hasResponse = "response" in response;
  const hasText = "text" in response; // generateText specific
  const hasObject = "object" in response; // generateObject specific

  return hasUsage || hasResponse || hasText || hasObject;
}

/**
 * Extract all span enrichment data from Vercel AI SDK response.
 *
 * This includes usage information, metadata, and output content.
 * Maps Vercel AI SDK format to Opik format using field mappings.
 *
 * @param response - The response from generateText or generateObject
 * @param languageModelId - The modelId from the LanguageModel instance
 * @returns Span fields to update (usage, metadata, output, model, provider)
 *
 * @see https://ai-sdk.dev/docs/reference/ai-sdk-core/generate-text#returns
 * @see https://ai-sdk.dev/docs/reference/ai-sdk-core/generate-object#returns
 */
export function enrichSpanFromResponse(
  response: unknown,
  languageModelId: string,
): Partial<UsageInfo> {
  // Early return for invalid responses
  if (!isVercelAIResponse(response)) {
    return { model: languageModelId };
  }

  // Build result object with only defined fields
  const result: Partial<UsageInfo> = {
    model: extractModelId(response, languageModelId),
  };

  const usage = extractTokenUsage(response.usage);
  if (usage) {
    result.usage = usage;
  }

  const provider = extractProvider(response);
  if (provider) {
    result.provider = provider;
  }

  const metadata = extractMetadata(response);
  if (metadata) {
    result.metadata = metadata;
  }

  const output = extractOutputFromResponse(response);
  if (output) {
    result.output = output;
  }

  return result;
}

/**
 * Extract model ID from response or use fallback
 */
function extractModelId(
  response: VercelAIResponse,
  fallbackModelId: string,
): string {
  return response.response?.modelId ?? fallbackModelId;
}

/**
 * Extract token usage from LanguageModelUsage and map to Opik format
 * In AI SDK v6, cached and reasoning tokens are nested in tokenDetails objects
 */
function extractTokenUsage(
  usage: LanguageModelUsage | undefined,
): Record<string, number> | undefined {
  if (!usage) {
    return undefined;
  }

  const opikUsage: Record<string, number> = {};

  // Map Vercel AI SDK token fields to Opik format
  for (const [vercelField, opikField] of Object.entries(TOKEN_FIELD_MAPPINGS)) {
    const tokenCount = usage[vercelField as keyof LanguageModelUsage];

    if (typeof tokenCount === "number") {
      opikUsage[opikField] = tokenCount;
    }
  }
  const typedUsage = usage as LanguageModelUsage & {
    inputTokenDetails?: {
      cacheReadTokens?: number;
    };
    outputTokenDetails?: {
      reasoningTokens?: number;
    };
  };

  const cacheReadTokens = typedUsage.inputTokenDetails?.cacheReadTokens;
  if (typeof cacheReadTokens === "number") {
    opikUsage.cached_input_tokens = cacheReadTokens;
  }

  const reasoningTokens = typedUsage.outputTokenDetails?.reasoningTokens;
  if (typeof reasoningTokens === "number") {
    opikUsage.reasoning_tokens = reasoningTokens;
  }

  return Object.keys(opikUsage).length > 0 ? opikUsage : undefined;
}

/**
 * Extract provider name from response metadata
 */
function extractProvider(response: VercelAIResponse): string | undefined {
  if (!response.providerMetadata) {
    return undefined;
  }

  const providerNames = Object.keys(response.providerMetadata);
  return providerNames.length > 0 ? providerNames[0] : undefined;
}

/**
 * Extract response metadata (id, timestamp)
 */
function extractResponseMetadata(
  responseMetadata: LanguageModelResponseMetadata | undefined,
): Record<string, unknown> | undefined {
  if (!responseMetadata) {
    return undefined;
  }

  const metadata: Record<string, unknown> = {};

  if (responseMetadata.id) {
    metadata.id = responseMetadata.id;
  }

  if (responseMetadata.timestamp) {
    metadata.timestamp = responseMetadata.timestamp.toISOString();
  }

  return Object.keys(metadata).length > 0 ? metadata : undefined;
}

/**
 * Extract additional metadata from Vercel AI SDK response.
 *
 * Collects:
 * - Full usage data (including all token counts)
 * - Warnings from the model provider
 * - Response ID and timestamp
 * - Provider-specific metadata
 * - Finish reason (why generation stopped)
 *
 * Returns a JSON stringified version of the metadata for compatibility
 * with the API's JsonListString type.
 */
function extractMetadata(
  response: VercelAIResponse,
): Record<string, unknown> | undefined {
  const metadata: Record<string, unknown> = {};

  // Include full usage data with all token counts
  if (response.usage) {
    metadata.usage = response.usage;
  }

  // Include warnings about unsupported settings or other issues
  if (response.warnings && response.warnings.length > 0) {
    metadata.warnings = response.warnings;
  }

  // Include response metadata (id, timestamp)
  const responseMetadata = extractResponseMetadata(response.response);
  if (responseMetadata) {
    metadata.response = responseMetadata;
  }

  // Include provider-specific metadata
  if (response.providerMetadata) {
    metadata.providerMetadata = response.providerMetadata;
  }

  // Include finish reason (why generation stopped)
  if (response.finishReason) {
    metadata.finishReason = response.finishReason;
  }

  return Object.keys(metadata).length > 0 ? metadata : undefined;
}

/**
 * Extract output content from Vercel AI SDK response.
 *
 * Extracts different output fields based on response type:
 * - text: from generateText responses
 * - object: from generateObject responses
 * - toolCalls: for agent scenarios with tool usage
 * - toolResults: for completed tool executions
 * - sources: for RAG models that cite sources
 *
 * @param response - The response from generateText or generateObject
 * @returns Output object with relevant fields, or undefined if no output
 */
function extractOutputFromResponse(
  response: VercelAIResponse,
): Record<string, unknown> | undefined {
  const output: Record<string, unknown> = {};

  // Extract text from generateText responses
  if ("text" in response && response.text) {
    output.text = response.text;
  }

  // Extract object from generateObject responses
  if ("object" in response && response.object !== undefined) {
    output.object = response.object;
  }

  // Extract tool calls for agent scenarios
  if ("toolCalls" in response && Array.isArray(response.toolCalls)) {
    output.toolCalls = response.toolCalls;
  }

  // Extract tool results for completed tool executions
  if ("toolResults" in response && Array.isArray(response.toolResults)) {
    output.toolResults = response.toolResults;
  }

  // Extract sources for RAG models
  if ("sources" in response && Array.isArray(response.sources)) {
    output.sources = response.sources;
  }

  return Object.keys(output).length > 0 ? output : undefined;
}
