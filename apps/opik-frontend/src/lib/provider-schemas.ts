import { PROVIDER_TYPE } from "@/types/providers";
import isString from "lodash/isString";
import isObject from "lodash/isObject";
import isArray from "lodash/isArray";
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

export interface ProviderFormatter {
  formatInput: (input: any) => PrettyViewData | null;
  formatOutput: (output: any) => PrettyViewData | null;
}

/**
 * OpenAI formatter for pretty view
 */
const openAIFormatter: ProviderFormatter = {
  formatInput: (input: any): PrettyViewData | null => {
    if (!isObject(input) || !("messages" in input) || !isArray(input.messages)) {
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
        (item: any) => isObject(item) && item.type === "text" && isString(item.text)
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
        model: input.model,
        temperature: input.temperature,
        max_tokens: input.max_tokens,
      },
    };
  },

  formatOutput: (output: any): PrettyViewData | null => {
    if (!isObject(output) || !("choices" in output) || !isArray(output.choices)) {
      return null;
    }

    const lastChoice = last(output.choices);
    if (!isObject(lastChoice) || !("message" in lastChoice)) {
      return null;
    }

    const message = lastChoice.message;
    if (!isObject(message) || !("content" in message) || !isString(message.content)) {
      return null;
    }

    return {
      content: message.content,
      metadata: {
        model: output.model,
        usage: output.usage,
        finish_reason: lastChoice.finish_reason,
      },
    };
  },
};

/**
 * Anthropic formatter for pretty view
 */
const anthropicFormatter: ProviderFormatter = {
  formatInput: (input: any): PrettyViewData | null => {
    if (!isObject(input) || !("messages" in input) || !isArray(input.messages)) {
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
        (item: any) => isObject(item) && item.type === "text" && isString(item.text)
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
        model: input.model,
        temperature: input.temperature,
        max_tokens: input.max_tokens,
      },
    };
  },

  formatOutput: (output: any): PrettyViewData | null => {
    if (!isObject(output) || !("content" in output)) {
      return null;
    }

    let content = "";
    if (isString(output.content)) {
      content = output.content;
    } else if (isArray(output.content)) {
      // Handle multimodal content
      const textContent = output.content.find(
        (item: any) => isObject(item) && item.type === "text" && isString(item.text)
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
        model: output.model,
        usage: output.usage,
        stop_reason: output.stop_reason,
      },
    };
  },
};

/**
 * Gemini formatter for pretty view
 */
const geminiFormatter: ProviderFormatter = {
  formatInput: (input: any): PrettyViewData | null => {
    if (!isObject(input) || !("contents" in input) || !isArray(input.contents)) {
      return null;
    }

    const lastContent = last(input.contents);
    if (!isObject(lastContent) || !("parts" in lastContent) || !isArray(lastContent.parts)) {
      return null;
    }

    const lastPart = last(lastContent.parts);
    if (!isObject(lastPart) || !("text" in lastPart) || !isString(lastPart.text)) {
      return null;
    }

    return {
      content: lastPart.text,
      metadata: {
        model: input.model,
        temperature: input.temperature,
        maxOutputTokens: input.maxOutputTokens,
      },
    };
  },

  formatOutput: (output: any): PrettyViewData | null => {
    if (!isObject(output) || !("candidates" in output) || !isArray(output.candidates)) {
      return null;
    }

    const lastCandidate = last(output.candidates);
    if (!isObject(lastCandidate) || !("content" in lastCandidate)) {
      return null;
    }

    const content = lastCandidate.content;
    if (!isObject(content) || !("parts" in content) || !isArray(content.parts)) {
      return null;
    }

    const lastPart = last(content.parts);
    if (!isObject(lastPart) || !("text" in lastPart) || !isString(lastPart.text)) {
      return null;
    }

    return {
      content: lastPart.text,
      metadata: {
        model: output.model,
        usage: output.usageMetadata,
        finishReason: lastCandidate.finishReason,
      },
    };
  },
};

/**
 * Vertex AI formatter for pretty view (similar to Gemini)
 */
const vertexAIFormatter: ProviderFormatter = {
  formatInput: (input: any): PrettyViewData | null => {
    // Vertex AI uses similar structure to Gemini
    return geminiFormatter.formatInput(input);
  },

  formatOutput: (output: any): PrettyViewData | null => {
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
 * Formats input/output data for a specific provider
 */
export const formatProviderData = (
  provider: PROVIDER_TYPE,
  data: any,
  type: "input" | "output"
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
  data: any,
  type: "input" | "output"
): boolean => {
  const formatted = formatProviderData(provider, data, type);
  return formatted !== null && formatted.content.length > 0;
};
