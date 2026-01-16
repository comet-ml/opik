import { ProviderDetector } from "../../types";

interface OpenAIMessage {
  role?: string;
  content?: unknown;
  tool_calls?: unknown[];
  tool_call_id?: string;
  name?: string;
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

  // Role must be one of the valid OpenAI roles
  const validRoles = ["system", "user", "assistant", "tool"];
  if (!validRoles.includes(m.role)) return false;

  // Content can be string, array, null, or undefined (for tool_calls)
  // Tool messages should have content
  if (m.role === "tool" && m.content === undefined) return false;

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
 * Detects if the provided data is in OpenAI format.
 * Supports both input format (messages array) and output format (choices array).
 */
export const detectOpenAIFormat: ProviderDetector = (data, prettifyConfig) => {
  // If data is null, undefined, or not an object, not supported
  if (!data || typeof data !== "object") {
    return false;
  }

  const isInput = prettifyConfig?.fieldType === "input";
  const isOutput = prettifyConfig?.fieldType === "output";

  // Only support input/output fields
  if (!isInput && !isOutput) {
    return false;
  }

  // Check for input format (messages array)
  if (isInput && hasOpenAIInputFormat(data)) {
    return true;
  }

  // Check for output format (choices array)
  if (isOutput && hasOpenAIOutputFormat(data)) {
    return true;
  }

  return false;
};
