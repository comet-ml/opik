/* eslint-disable @typescript-eslint/no-explicit-any */
import { PROVIDER_TYPE } from "@/types/providers";
import isString from "lodash/isString";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
import isNumber from "lodash/isNumber";
import last from "lodash/last";

/**
 * Provider-specific response interfaces
 */

// OpenAI interfaces
interface OpenAIMessage {
  role: string;
  content: string;
}

interface OpenAIChoice {
  message: OpenAIMessage;
  finish_reason?: string;
}

interface OpenAIUsage {
  prompt_tokens?: number;
  completion_tokens?: number;
  total_tokens?: number;
}

interface OpenAIInput {
  messages: OpenAIMessage[];
  model?: string;
  temperature?: number;
  max_tokens?: number;
}

interface OpenAIOutput {
  choices: OpenAIChoice[];
  model?: string;
  usage?: OpenAIUsage;
}

// Anthropic interfaces
interface AnthropicMessage {
  role: string;
  content: string;
}

interface AnthropicUsage {
  input_tokens?: number;
  output_tokens?: number;
}

interface AnthropicInput {
  messages: AnthropicMessage[];
  model?: string;
  temperature?: number;
  max_tokens?: number;
}

interface AnthropicOutput {
  content: string;
  model?: string;
  usage?: AnthropicUsage;
  stop_reason?: string;
}

// Gemini interfaces
interface GeminiPart {
  text: string;
}

interface GeminiContent {
  parts: GeminiPart[];
}

interface GeminiUsage {
  promptTokenCount?: number;
  candidatesTokenCount?: number;
  totalTokenCount?: number;
}

interface GeminiInput {
  contents: GeminiContent[];
  model?: string;
  temperature?: number;
  maxOutputTokens?: number;
}

interface GeminiCandidate {
  content: GeminiContent;
  finishReason?: string;
}

interface GeminiOutput {
  candidates: GeminiCandidate[];
  model?: string;
  usageMetadata?: GeminiUsage;
}

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
    stop_reason?: string;
    finishReason?: string;
    temperature?: number;
    max_tokens?: number;
    maxOutputTokens?: number;
  };
}

/**
 * Normalizes usage data from different providers to a standard format
 */
const normalizeUsageData = (
  usage: OpenAIUsage | AnthropicUsage | GeminiUsage | unknown,
): NonNullable<PrettyViewData["metadata"]>["usage"] | undefined => {
  if (!isObject(usage)) return undefined;

  const normalized: NonNullable<PrettyViewData["metadata"]>["usage"] = {};

  // OpenAI format: prompt_tokens, completion_tokens, total_tokens
  if ("prompt_tokens" in usage && isNumber(usage.prompt_tokens)) {
    normalized.prompt_tokens = usage.prompt_tokens;
  }
  if ("completion_tokens" in usage && isNumber(usage.completion_tokens)) {
    normalized.completion_tokens = usage.completion_tokens;
  }
  if ("total_tokens" in usage && isNumber(usage.total_tokens)) {
    normalized.total_tokens = usage.total_tokens;
  }

  // Anthropic format: input_tokens, output_tokens
  if ("input_tokens" in usage && isNumber(usage.input_tokens)) {
    normalized.prompt_tokens = usage.input_tokens;
  }
  if ("output_tokens" in usage && isNumber(usage.output_tokens)) {
    normalized.completion_tokens = usage.output_tokens;
  }

  // Gemini format: promptTokenCount, candidatesTokenCount, totalTokenCount
  if ("promptTokenCount" in usage && isNumber(usage.promptTokenCount)) {
    normalized.prompt_tokens = usage.promptTokenCount;
  }
  if ("candidatesTokenCount" in usage && isNumber(usage.candidatesTokenCount)) {
    normalized.completion_tokens = usage.candidatesTokenCount;
  }
  if ("totalTokenCount" in usage && isNumber(usage.totalTokenCount)) {
    normalized.total_tokens = usage.totalTokenCount;
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

    const openAIInput = input as OpenAIInput;
    const lastMessage = last(openAIInput.messages);
    if (!isObject(lastMessage) || !("content" in lastMessage)) {
      return null;
    }

    let content = "";
    if (isString(lastMessage.content)) {
      content = lastMessage.content;
    } else if (isArray(lastMessage.content)) {
      // Handle multimodal content
      const textContent = (lastMessage.content as unknown[]).find(
        (item: unknown) => {
          if (!isObject(item)) return false;
          const obj = item as any;
          return obj.type === "text" && isString(obj.text);
        },
      );
      if (textContent && isObject(textContent) && "text" in textContent) {
        content = (textContent as { text: string }).text;
      }
    }

    if (!content) {
      return null;
    }

    return {
      content,
      metadata: {
        model: openAIInput.model,
        temperature: openAIInput.temperature,
        max_tokens: openAIInput.max_tokens,
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

    const openAIOutput = output as OpenAIOutput;
    const lastChoice = last(openAIOutput.choices);
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
        model: openAIOutput.model,
        usage: normalizeUsageData(openAIOutput.usage),
        finish_reason: lastChoice.finish_reason,
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

    const anthropicInput = input as AnthropicInput;
    const lastMessage = last(anthropicInput.messages);
    if (!isObject(lastMessage) || !("content" in lastMessage)) {
      return null;
    }

    let content = "";
    if (isString(lastMessage.content)) {
      content = lastMessage.content;
    } else if (isArray(lastMessage.content)) {
      // Handle multimodal content
      const textContent = (lastMessage.content as unknown[]).find(
        (item: unknown) => {
          if (!isObject(item)) return false;
          const obj = item as any;
          return obj.type === "text" && isString(obj.text);
        },
      );
      if (textContent && isObject(textContent) && "text" in textContent) {
        content = (textContent as { text: string }).text;
      }
    }

    if (!content) {
      return null;
    }

    return {
      content,
      metadata: {
        model: anthropicInput.model,
        temperature: anthropicInput.temperature,
        max_tokens: anthropicInput.max_tokens,
      },
    };
  },

  formatOutput: (output: unknown): PrettyViewData | null => {
    if (!isObject(output) || !("content" in output)) {
      return null;
    }

    const anthropicOutput = output as AnthropicOutput;
    let content = "";
    if (isString(anthropicOutput.content)) {
      content = anthropicOutput.content;
    } else if (isArray(anthropicOutput.content)) {
      // Handle multimodal content
      const textContent = (anthropicOutput.content as unknown[]).find(
        (item: unknown) => {
          if (!isObject(item)) return false;
          const obj = item as any;
          return obj.type === "text" && isString(obj.text);
        },
      );
      if (textContent && isObject(textContent) && "text" in textContent) {
        content = (textContent as { text: string }).text;
      }
    }

    if (!content) {
      return null;
    }

    return {
      content,
      metadata: {
        model: anthropicOutput.model,
        usage: normalizeUsageData(anthropicOutput.usage),
        stop_reason: anthropicOutput.stop_reason,
      },
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

    const geminiInput = input as GeminiInput;
    const lastContent = last(geminiInput.contents);
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
        model: geminiInput.model,
        temperature: geminiInput.temperature,
        maxOutputTokens: geminiInput.maxOutputTokens,
      },
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

    const geminiOutput = output as GeminiOutput;
    const lastCandidate = last(geminiOutput.candidates);
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
        model: geminiOutput.model,
        usage: normalizeUsageData(geminiOutput.usageMetadata),
        finishReason: lastCandidate.finishReason,
      },
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
