import type { ChunkResult, GeminiResponse } from "./types";
import { flattenObject, normalizeModelName } from "./utils";

/**
 * Parse input arguments from Gemini SDK call
 * Extracts: model, input (contents + config), modelParameters (config details for metadata)
 *
 * Note: Following Python SDK pattern - both contents and config are logged as input
 */
export const parseInputArgs = (
  args: Record<string, unknown>
): {
  model: string | undefined;
  input: Record<string, unknown>;
  modelParameters: Record<string, unknown>;
} => {
  // Extract model name
  let model = args.model as string | undefined;
  if (model) {
    model = normalizeModelName(model);
  }

  // Extract "contents" and "config" fields for input (matching Python SDK)
  const input: Record<string, unknown> = {};
  if ("contents" in args) {
    input.contents = args.contents;
  }
  if ("prompt" in args) {
    input.prompt = args.prompt;
  }
  // Include config in input to match Python SDK behavior
  if ("config" in args) {
    input.config = args.config;
  }

  // Extract "config" field for model parameters
  const modelParameters: Record<string, unknown> = {};
  if (
    "config" in args &&
    typeof args.config === "object" &&
    args.config !== null
  ) {
    const config = args.config as Record<string, unknown>;

    // Extract system instruction
    if ("systemInstruction" in config) {
      modelParameters.systemInstruction = config.systemInstruction;
    }

    // Extract generation config
    if ("generationConfig" in config) {
      modelParameters.generationConfig = config.generationConfig;
    }

    // Extract safety settings
    if ("safetySettings" in config) {
      modelParameters.safetySettings = config.safetySettings;
    }

    // Extract tools
    if ("tools" in config) {
      modelParameters.tools = config.tools;
    }

    // Extract tool config
    if ("toolConfig" in config) {
      modelParameters.toolConfig = config.toolConfig;
    }

    // Extract other config fields
    const configKeys = [
      "temperature",
      "maxOutputTokens",
      "topP",
      "topK",
      "candidateCount",
      "stopSequences",
    ];
    for (const key of configKeys) {
      if (key in config) {
        modelParameters[key] = config[key as keyof typeof config];
      }
    }
  }

  return { model, input, modelParameters };
};

/**
 * Parse completion output from Gemini response
 * Returns only candidates array to match Python SDK behavior
 */
export const parseCompletionOutput = (
  res: unknown
): Record<string, unknown> | undefined => {
  if (!res || typeof res !== "object") {
    return undefined;
  }

  const response = res as GeminiResponse;

  // Extract "candidates" field only (matching Python SDK)
  if ("candidates" in response && Array.isArray(response.candidates)) {
    const candidates = response.candidates;

    if (candidates.length === 0) {
      return undefined;
    }

    // Return only candidates, not content (matching Python SDK)
    return { candidates: response.candidates };
  }

  return undefined;
};

/**
 * Parse usage metadata from Gemini response
 * Maps Gemini fields to Opik format
 */
export const parseUsage = (
  res: unknown
): Record<string, number> | undefined => {
  if (!res || typeof res !== "object") {
    return undefined;
  }

  const response = res as GeminiResponse;

  // Extract "usageMetadata" or "usage_metadata" field
  const usageMetadata =
    ("usageMetadata" in response && response.usageMetadata) ||
    ("usage_metadata" in response && response.usage_metadata) ||
    ("usage" in response && response.usage);

  if (!usageMetadata || typeof usageMetadata !== "object") {
    return undefined;
  }

  const usage = usageMetadata as Record<string, unknown>;

  // Map Gemini fields to Opik format:
  // prompt_token_count → prompt_tokens
  // candidates_token_count → completion_tokens
  // total_token_count → total_tokens
  const result: Record<string, number> = {};

  if (
    typeof usage.promptTokenCount === "number" ||
    typeof usage.prompt_token_count === "number"
  ) {
    result.prompt_tokens = (usage.promptTokenCount ||
      usage.prompt_token_count) as number;
  }

  if (
    typeof usage.candidatesTokenCount === "number" ||
    typeof usage.candidates_token_count === "number"
  ) {
    result.completion_tokens = (usage.candidatesTokenCount ||
      usage.candidates_token_count) as number;
  }

  if (
    typeof usage.totalTokenCount === "number" ||
    typeof usage.total_token_count === "number"
  ) {
    result.total_tokens = (usage.totalTokenCount ||
      usage.total_token_count) as number;
  }

  // Include cached content tokens if present
  if (
    typeof usage.cachedContentTokenCount === "number" ||
    typeof usage.cached_content_token_count === "number"
  ) {
    result.cached_content_tokens = (usage.cachedContentTokenCount ||
      usage.cached_content_token_count) as number;
  }

  // Flatten original usage metadata, but exclude token detail objects
  // Note: promptTokensDetails and candidatesTokensDetails are nested objects
  // that cannot be included because the API expects all usage values to be integers
  const usageToFlatten = { ...usage };

  // Remove token details objects before flattening - they're nested objects
  // that would violate the API schema (usage must have integer values only)
  delete usageToFlatten.promptTokensDetails;
  delete usageToFlatten.prompt_tokens_details;
  delete usageToFlatten.candidatesTokensDetails;
  delete usageToFlatten.candidates_tokens_details;

  // Flatten the remaining usage fields (all numeric values)
  const flattenedUsage = flattenObject(usageToFlatten, "original_usage");

  // Filter to ensure only numeric values are included (API requirement)
  for (const [key, value] of Object.entries(flattenedUsage)) {
    if (typeof value === "number") {
      result[key] = value;
    }
  }

  return Object.keys(result).length > 0 ? result : undefined;
};

/**
 * Parse a streaming chunk from Gemini
 * Extracts text or tool call data
 */
export const parseChunk = (rawChunk: unknown): ChunkResult => {
  if (!rawChunk || typeof rawChunk !== "object") {
    return { isToolCall: false, data: "" };
  }

  const chunk = rawChunk as GeminiResponse;

  // Extract text from chunk.candidates[0].content.parts[0].text
  if (
    "candidates" in chunk &&
    Array.isArray(chunk.candidates) &&
    chunk.candidates.length > 0
  ) {
    const candidate = chunk.candidates[0];

    if (
      candidate &&
      "content" in candidate &&
      candidate.content &&
      typeof candidate.content === "object"
    ) {
      const content = candidate.content;

      if (
        "parts" in content &&
        Array.isArray(content.parts) &&
        content.parts.length > 0
      ) {
        const part = content.parts[0];

        // Check for function/tool call
        if ("functionCall" in part || "function_call" in part) {
          return {
            isToolCall: true,
            data: JSON.stringify(part.functionCall || part.function_call),
          };
        }

        // Extract text
        if ("text" in part && typeof part.text === "string") {
          return {
            isToolCall: false,
            data: part.text,
          };
        }
      }
    }
  }

  return { isToolCall: false, data: "" };
};

/**
 * Parse model data from Gemini response
 * Extracts model version and metadata (safety ratings, finish reason, etc.)
 */
export const parseModelDataFromResponse = (
  res: unknown
): {
  model: string | undefined;
  metadata: Record<string, unknown> | undefined;
} => {
  if (!res || typeof res !== "object") {
    return { model: undefined, metadata: undefined };
  }

  const response = res as GeminiResponse;
  let model: string | undefined;
  const metadata: Record<string, unknown> = {};

  // Extract model_version and strip "models/" prefix
  if ("modelVersion" in response && typeof response.modelVersion === "string") {
    model = normalizeModelName(response.modelVersion);
  } else if (
    "model_version" in response &&
    typeof response.model_version === "string"
  ) {
    model = normalizeModelName(response.model_version);
  }

  // Extract safety ratings from first candidate
  if (
    "candidates" in response &&
    Array.isArray(response.candidates) &&
    response.candidates.length > 0
  ) {
    const candidate = response.candidates[0];

    if ("safetyRatings" in candidate) {
      metadata.safety_ratings = candidate.safetyRatings;
    } else if ("safety_ratings" in candidate) {
      metadata.safety_ratings = candidate.safety_ratings;
    }

    if ("finishReason" in candidate) {
      metadata.finish_reason = candidate.finishReason;
    } else if ("finish_reason" in candidate) {
      metadata.finish_reason = candidate.finish_reason;
    }
  }

  // Extract prompt feedback
  if ("promptFeedback" in response) {
    metadata.prompt_feedback = response.promptFeedback;
  } else if ("prompt_feedback" in response) {
    metadata.prompt_feedback = response.prompt_feedback;
  }

  // Add created_from marker
  metadata.created_from = "genai";

  return {
    model,
    metadata: Object.keys(metadata).length > 0 ? metadata : undefined,
  };
};
