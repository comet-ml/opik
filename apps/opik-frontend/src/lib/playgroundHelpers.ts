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

  // Get valid roles once, outside the loop
  const validRoles = Object.values(LLM_MESSAGE_ROLE);

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
    if (!validRoles.includes(msg.role as LLM_MESSAGE_ROLE)) {
      return false;
    }

    // Check if message has content property
    if (!("content" in msg)) {
      return false;
    }

    // Content can be:
    // 1. string (simple text message)
    // 2. null/undefined (some assistant messages)
    // 3. array of content parts (multimodal messages like [{type: 'text', text: '...'}])
    if (msg.content !== null && msg.content !== undefined) {
      const isString = typeof msg.content === "string";
      const isArray = Array.isArray(msg.content);
      
      if (!isString && !isArray) {
        return false;
      }

      // If it's an array, validate it's an array of content parts
      if (isArray) {
        const contentParts = msg.content as unknown[];
        if (contentParts.length === 0) {
          return false;
        }
        // Basic validation: each part should be an object
        const allPartsValid = contentParts.every(
          (part) => part && typeof part === "object"
        );
        if (!allPartsValid) {
          return false;
        }
      }
    }

    return true;
  });
};

/**
 * Extracts text content from a message content field
 * Handles both string content and array of content parts
 */
const extractTextContent = (
  content: string | unknown[] | null | undefined
): string => {
  if (content === null || content === undefined) {
    return "";
  }

  if (typeof content === "string") {
    return content;
  }

  if (Array.isArray(content)) {
    // Extract text from content parts array
    // Example: [{ type: 'text', text: 'Hello' }, { type: 'image_url', image_url: {...} }]
    const textParts = content
      .filter((part) => {
        if (!part || typeof part !== "object") return false;
        const p = part as Record<string, unknown>;
        return p.type === "text" && typeof p.text === "string";
      })
      .map((part) => {
        const p = part as Record<string, unknown>;
        return p.text as string;
      });

    return textParts.join("\n");
  }

  return "";
};

/**
 * Converts span input messages to LLMMessage format
 */
export const convertSpanMessagesToLLMMessages = (span: Span): LLMMessage[] => {
  if (!hasValidMessagesFormat(span)) {
    return [];
  }

  const input = span.input as Record<string, unknown>;
  const messages = input.messages as Array<{
    role: string;
    content: string | unknown[] | null | undefined;
  }>;

  return messages.map((message) => ({
    id: generateRandomString(),
    role: message.role as LLM_MESSAGE_ROLE,
    content: extractTextContent(message.content),
  }));
};