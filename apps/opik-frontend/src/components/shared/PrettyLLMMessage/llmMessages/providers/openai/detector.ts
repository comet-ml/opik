import { ProviderDetector } from "../../types";

interface OpenAIMessage {
  role?: string;
  content?: unknown;
  tool_calls?: unknown[];
  tool_call_id?: string;
  name?: string;
}

interface OpenAICustomInputMessage {
  role?: string;
  text?: unknown;
  files?: unknown[];
}

interface OpenAIChoice {
  message?: OpenAIMessage;
  index?: number;
}

/**
 * Checks if a message object has the OpenAI message format structure
 */
const isOpenAIMessage = (msg: unknown): msg is OpenAIMessage => {
  if (!msg || typeof msg !== "object") return false;
  const m = msg as Record<string, unknown>;

  // Must have a role field
  if (!m.role || typeof m.role !== "string") return false;

  // Role must be one of the valid OpenAI roles (including legacy "function" role)
  const validRoles = ["system", "user", "assistant", "tool", "function"];
  if (!validRoles.includes(m.role)) return false;

  // Content can be string, array, null, or undefined (for tool_calls)
  // Tool messages should have content
  if (m.role === "tool" && m.content === undefined) return false;

  return true;
};

/**
 * Checks if a message object has the custom input format structure with text/files
 */
const isCustomInputMessage = (
  msg: unknown,
): msg is OpenAICustomInputMessage => {
  if (!msg || typeof msg !== "object") return false;
  const m = msg as Record<string, unknown>;

  // Must have a role field
  if (!m.role || typeof m.role !== "string") return false;

  // Role must be one of the valid OpenAI roles (including legacy "function" role)
  const validRoles = ["system", "user", "assistant", "tool", "function"];
  if (!validRoles.includes(m.role)) return false;

  // Must have text field (content equivalent)
  if (m.text === undefined) return false;

  return true;
};

/**
 * Checks if an object has OpenAI chat completion input format
 * (messages array with role/content objects)
 */
const hasOpenAIInputFormat = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  // Check for messages array
  if (!Array.isArray(d.messages)) return false;
  if (d.messages.length === 0) return false;

  // Check that all messages have valid OpenAI message structure
  return d.messages.every(isOpenAIMessage);
};

/**
 * Checks if data is a direct array of OpenAI messages
 */
const isOpenAIMessageArray = (data: unknown): boolean => {
  if (!Array.isArray(data)) return false;
  if (data.length === 0) return false;

  // Check that all items are valid OpenAI messages
  return data.every(isOpenAIMessage);
};

/**
 * Checks if an object has custom input format
 * (input array with text/files objects)
 */
const hasCustomInputFormat = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  // Check for input array
  if (!Array.isArray(d.input)) return false;
  if (d.input.length === 0) return false;

  // Check that all messages have valid custom input message structure
  return d.input.every(isCustomInputMessage);
};

/**
 * Checks if an object has OpenAI chat completion output format
 * (choices array with message objects)
 */
const hasOpenAIOutputFormat = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  // Check for choices array
  if (!Array.isArray(d.choices)) return false;
  if (d.choices.length === 0) return false;

  // Check that all choices have a valid message
  return d.choices.every((choice: unknown) => {
    if (!choice || typeof choice !== "object") return false;
    const c = choice as OpenAIChoice;
    return c.message && isOpenAIMessage(c.message);
  });
};

/**
 * Checks if an object has custom output format
 * (text field with optional usage and finish_reason)
 */
const hasCustomOutputFormat = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  // Must have a text field
  if (typeof d.text !== "string") return false;

  // Optional fields that commonly appear with this format
  // If they exist, they should be the right type
  if (d.usage !== undefined && typeof d.usage !== "object") return false;
  if (d.finish_reason !== undefined && typeof d.finish_reason !== "string")
    return false;

  return true;
};

/**
 * Checks if an object has conversation output format
 * (messages array with at least one assistant message)
 * Used by integrations like OpenWebUI that store the full conversation as output
 */
const hasConversationOutputFormat = (data: unknown): boolean => {
  if (!data || typeof data !== "object") return false;
  const d = data as Record<string, unknown>;

  if (!Array.isArray(d.messages)) return false;
  if (d.messages.length === 0) return false;
  if (!d.messages.every(isOpenAIMessage)) return false;

  return d.messages.some(
    (msg: unknown) =>
      typeof msg === "object" &&
      msg !== null &&
      (msg as OpenAIMessage).role === "assistant",
  );
};

/**
 * Detects if the provided data is in OpenAI format.
 * Supports multiple input and output formats.
 */
export const detectOpenAIFormat: ProviderDetector = (data, prettifyConfig) => {
  // If data is null or undefined, not supported
  if (!data) {
    return false;
  }

  const isInput = prettifyConfig?.fieldType === "input";
  const isOutput = prettifyConfig?.fieldType === "output";

  // Only support input/output fields
  if (!isInput && !isOutput) {
    return false;
  }

  // Check for input formats
  if (isInput) {
    // Standard format: { messages: [...] }
    if (hasOpenAIInputFormat(data)) {
      return true;
    }
    // Direct array format: [{ role: "user", content: "..." }]
    if (isOpenAIMessageArray(data)) {
      return true;
    }
    // Custom input format: { input: [{ role: "system", text: "...", files: [] }] }
    if (hasCustomInputFormat(data)) {
      return true;
    }
  }

  // Check for output formats
  if (isOutput) {
    // Standard format: { choices: [...] }
    if (hasOpenAIOutputFormat(data)) {
      return true;
    }
    // Custom output format: { text: "...", usage: {...}, finish_reason: "..." }
    if (hasCustomOutputFormat(data)) {
      return true;
    }
    // Conversation format: { messages: [...] } with at least one assistant message
    // Used by integrations like OpenWebUI that store the full conversation as output
    if (hasConversationOutputFormat(data)) {
      return true;
    }
  }

  return false;
};
