import { PROVIDER_TYPE } from "@/types/providers";
import isString from "lodash/isString";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import isNumber from "lodash/isNumber";
import last from "lodash/last";

export interface PrettyViewData {
  content: string;
  metadata?: {
    model?: string;
    usage?: {
      prompt_tokens?: number;
      completion_tokens?: number;
      total_tokens?: number;
    };
    finish_reason?: string;
    temperature?: number;
    max_tokens?: number;
  };
}

/**
 * Normalizes usage data from different providers to a standard format
 */
const normalizeUsageData = (
  usage: unknown,
): any => {
  if (!isObject(usage)) return undefined;

  const normalized: any = {};

  // OpenAI format: prompt_tokens, completion_tokens, total_tokens
  if (isNumber((usage as any).prompt_tokens)) {
    normalized.prompt_tokens = (usage as any).prompt_tokens;
  }
  if (isNumber((usage as any).completion_tokens)) {
    normalized.completion_tokens = (usage as any).completion_tokens;
  }
  if (isNumber((usage as any).total_tokens)) {
    normalized.total_tokens = (usage as any).total_tokens;
  }

  // Anthropic format: input_tokens, output_tokens
  if (isNumber((usage as any).input_tokens)) {
    normalized.prompt_tokens = (usage as any).input_tokens;
  }
  if (isNumber((usage as any).output_tokens)) {
    normalized.completion_tokens = (usage as any).output_tokens;
  }

  // Gemini format: promptTokenCount, candidatesTokenCount, totalTokenCount
  if (isNumber((usage as any).promptTokenCount)) {
    normalized.prompt_tokens = (usage as any).promptTokenCount;
  }
  if (isNumber((usage as any).candidatesTokenCount)) {
    normalized.completion_tokens = (usage as any).candidatesTokenCount;
  }
  if (isNumber((usage as any).totalTokenCount)) {
    normalized.total_tokens = (usage as any).totalTokenCount;
  }

  // Calculate total if not provided
  if (
    !normalized.total_tokens &&
    normalized.prompt_tokens &&
    normalized.completion_tokens
  ) {
    normalized.total_tokens =
      normalized.prompt_tokens + normalized.completion_tokens;
  }

  // Return undefined if no usage data was found
  return Object.keys(normalized).length > 0 ? normalized : undefined;
};

export interface ProviderFormatter {
  formatInput: (input: unknown) => PrettyViewData | null;
  formatOutput: (output: unknown) => PrettyViewData | null;
}

/**
 * OpenAI formatter for pretty view
 */
const openAIFormatter: ProviderFormatter = {
  formatInput: (input: unknown): PrettyViewData | null => {
    if (
      !isObject(input) ||
      !("messages" in input) ||
      !isArray(input.messages)
    ) {
      return null;
    }

    const lastMessage = last(input.messages);
    if (!isObject(lastMessage) || !("content" in lastMessage)) {
      return null;
    }

    let content = "";
    if (isString(lastMessage.content)) {
      content = lastMessage.content;
    } else if (isArray(lastMessage.content)) {
      // Handle multimodal content
      const textContent = lastMessage.content.find(
        (item: unknown) =>
          isObject(item) && (item as any).type === "text" && isString((item as any).text),
      );
      if (textContent) {
        content = textContent.text;
      }
    }

    if (!content) {
      return null;
    }

    return {
      content,
      metadata: {
        model: (input as any).model,
        temperature: (input as any).temperature,
        max_tokens: (input as any).max_tokens,
      },
    };
  },

  formatOutput: (output: unknown): PrettyViewData | null => {
    if (
      !isObject(output) ||
      !("choices" in output) ||
      !isArray(output.choices)
    ) {
      return null;
    }

    const lastChoice = last(output.choices);
    if (!isObject(lastChoice) || !("message" in lastChoice)) {
      return null;
    }

    const message = lastChoice.message;
    if (
      !isObject(message) ||
      !("content" in message) ||
      !isString(message.content)
    ) {
      return null;
    }

    return {
      content: message.content,
      metadata: {
        model: (output as any).model,
        usage: normalizeUsageData((output as any).usage),
        finish_reason: (lastChoice as any).finish_reason,
      },
    };
  },
};

/**
 * Anthropic formatter for pretty view
 */
const anthropicFormatter: ProviderFormatter = {
  formatInput: (input: unknown): PrettyViewData | null => {
    if (
      !isObject(input) ||
      !("messages" in input) ||
      !isArray(input.messages)
    ) {
      return null;
    }

    const lastMessage = last(input.messages);
    if (!isObject(lastMessage) || !("content" in lastMessage)) {
      return null;
    }

    let content = "";
    if (isString(lastMessage.content)) {
      content = lastMessage.content;
    } else if (isArray(lastMessage.content)) {
      // Handle multimodal content
      const textContent = lastMessage.content.find(
        (item: unknown) =>
          isObject(item) && (item as any).type === "text" && isString((item as any).text),
      );
      if (textContent) {
        content = textContent.text;
      }
    }

    if (!content) {
      return null;
    }

    return {
      content,
      metadata: {
        model: (input as any).model,
        temperature: (input as any).temperature,
        max_tokens: (input as any).max_tokens,
      },
    };
  },

  formatOutput: (output: unknown): PrettyViewData | null => {
    if (!isObject(output) || !("content" in output)) {
      return null;
    }

    let content = "";
    if (isString(output.content)) {
      content = output.content;
    } else if (isArray(output.content)) {
      // Handle multimodal content
      const textContent = output.content.find(
        (item: unknown) =>
          isObject(item) && (item as any).type === "text" && isString((item as any).text),
      );
      if (textContent) {
        content = textContent.text;
      }
    }

    if (!content) {
      return null;
    }

    return {
      content,
      metadata: {
        model: (output as any).model,
        usage: normalizeUsageData((output as any).usage),
        stop_reason: (output as any).stop_reason,
      } as any,
    };
  },
};

/**
 * Gemini formatter for pretty view
 */
const geminiFormatter: ProviderFormatter = {
  formatInput: (input: unknown): PrettyViewData | null => {
    if (
      !isObject(input) ||
      !("contents" in input) ||
      !isArray(input.contents)
    ) {
      return null;
    }

    const lastContent = last(input.contents);
    if (
      !isObject(lastContent) ||
      !("parts" in lastContent) ||
      !isArray(lastContent.parts)
    ) {
      return null;
    }

    const lastPart = last(lastContent.parts);
    if (
      !isObject(lastPart) ||
      !("text" in lastPart) ||
      !isString(lastPart.text)
    ) {
      return null;
    }

    return {
      content: lastPart.text,
      metadata: {
        model: (input as any).model,
        temperature: (input as any).temperature,
        maxOutputTokens: (input as any).maxOutputTokens,
      } as any,
    };
  },

  formatOutput: (output: unknown): PrettyViewData | null => {
    if (
      !isObject(output) ||
      !("candidates" in output) ||
      !isArray(output.candidates)
    ) {
      return null;
    }

    const lastCandidate = last(output.candidates);
    if (!isObject(lastCandidate) || !("content" in lastCandidate)) {
      return null;
    }

    const content = lastCandidate.content;
    if (
      !isObject(content) ||
      !("parts" in content) ||
      !isArray(content.parts)
    ) {
      return null;
    }

    const lastPart = last(content.parts);
    if (
      !isObject(lastPart) ||
      !("text" in lastPart) ||
      !isString(lastPart.text)
    ) {
      return null;
    }

    return {
      content: lastPart.text,
      metadata: {
        model: (output as any).model,
        usage: normalizeUsageData((output as any).usageMetadata),
        finishReason: (lastCandidate as any).finishReason,
      } as any,
    };
  },
};

/**
 * Vertex AI formatter for pretty view (similar to Gemini)
 */
const vertexAIFormatter: ProviderFormatter = {
  formatInput: (input: unknown): PrettyViewData | null => {
    // Vertex AI uses similar structure to Gemini
    return geminiFormatter.formatInput(input);
  },

  formatOutput: (output: unknown): PrettyViewData | null => {
    // Vertex AI uses similar structure to Gemini
    return geminiFormatter.formatOutput(output);
  },
};

/**
 * Provider formatters registry
 */
const providerFormatters: Record<PROVIDER_TYPE, ProviderFormatter> = {
  [PROVIDER_TYPE.OPEN_AI]: openAIFormatter,
  [PROVIDER_TYPE.ANTHROPIC]: anthropicFormatter,
  [PROVIDER_TYPE.GEMINI]: geminiFormatter,
  [PROVIDER_TYPE.VERTEX_AI]: vertexAIFormatter,
  [PROVIDER_TYPE.OPEN_ROUTER]: openAIFormatter, // OpenRouter is OpenAI-compatible
  [PROVIDER_TYPE.CUSTOM]: openAIFormatter, // Custom providers are typically OpenAI-compatible
};

/**
 * Get list of providers that support pretty view formatting
 * Derived from providerFormatters to ensure consistency
 */
export const getSupportedProviders = (): PROVIDER_TYPE[] => {
  return Object.keys(providerFormatters) as PROVIDER_TYPE[];
};

/**
 * Check if a provider supports pretty view formatting
 */
export const supportsPrettyView = (provider: PROVIDER_TYPE): boolean => {
  return provider in providerFormatters;
};

/**
 * Formats input/output data for a specific provider
 */
export const formatProviderData = (
  provider: PROVIDER_TYPE,
  data: unknown,
  type: "input" | "output",
): PrettyViewData | null => {
  const formatter = providerFormatters[provider];
  if (!formatter) {
    return null;
  }

  if (type === "input") {
    return formatter.formatInput(data);
  } else {
    return formatter.formatOutput(data);
  }
};

/**
 * Checks if data can be formatted for a specific provider
 */
export const canFormatProviderData = (
  provider: PROVIDER_TYPE,
  data: unknown,
  type: "input" | "output",
): boolean => {
  const formatted = formatProviderData(provider, data, type);
  return formatted !== null && formatted.content.length > 0;
};
