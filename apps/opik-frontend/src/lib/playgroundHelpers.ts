import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { Span } from "@/types/traces";
import { generateRandomString } from "@/lib/utils";

/**
 * Checks if a span's input contains a messages array in the OpenAI format
 * (array of objects with role and content properties)
 */
export const hasValidMessagesFormat = (span: Span): boolean => {
  if (!span || !span.input) {
    return false;
  }

  const input = span.input as Record<string, unknown>;
  const messages = input.messages;

  // Check if messages is an array
  if (!Array.isArray(messages) || messages.length === 0) {
    return false;
  }

  // Check if all messages have the required structure
  return messages.every((message: unknown) => {
    if (!message || typeof message !== "object") {
      return false;
    }

    const msg = message as Record<string, unknown>;

    // Check if message has role property
    if (!msg.role || typeof msg.role !== "string") {
      return false;
    }

    // Check if role is a valid LLM message role
    const validRoles = Object.values(LLM_MESSAGE_ROLE);
    if (!validRoles.includes(msg.role as LLM_MESSAGE_ROLE)) {
      return false;
    }

    // Check if message has content property
    if (!("content" in msg)) {
      return false;
    }

    // Content can be string or null/undefined for some assistant messages
    if (
      msg.content !== null &&
      msg.content !== undefined &&
      typeof msg.content !== "string"
    ) {
      return false;
    }

    return true;
  });
};

/**
 * Converts span input messages to LLMMessage format
 */
export const convertSpanMessagesToLLMMessages = (span: Span): LLMMessage[] => {
  if (!hasValidMessagesFormat(span)) {
    return [];
  }

  const input = span.input as Record<string, unknown>;
  const messages = input.messages as Array<{ role: string; content: string }>;

  return messages.map((message) => ({
    id: generateRandomString(),
    role: message.role as LLM_MESSAGE_ROLE,
    content: message.content || "",
  }));
};
