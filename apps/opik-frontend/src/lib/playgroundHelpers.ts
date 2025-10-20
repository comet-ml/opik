import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { Span } from "@/types/traces";
import { generateRandomString } from "@/lib/utils";

/**
 * Valid Anthropic message roles including standard ones plus Anthropic-specific role names
 */
const VALID_ANTHROPIC_ROLES = [
  "system",
  "user", 
  "assistant",
  "human", // Anthropic sometimes uses 'human' instead of 'user'
  "ai", // Anthropic sometimes uses 'ai' instead of 'assistant'
];

/**
 * Checks if a span's input contains a messages array in the OpenAI format
 * (array of objects with role and content properties)
 */
export const hasValidOpenAIMessagesFormat = (span: Span): boolean => {
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
          (part) => part && typeof part === "object",
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
 * Checks if a span's input contains a messages array in the Anthropic format
 * Anthropic messages follow similar structure but may have different role names and content structure
 */
export const hasValidAnthropicMessagesFormat = (span: Span): boolean => {
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

    // Check if role is a valid Anthropic message role
    if (!VALID_ANTHROPIC_ROLES.includes(msg.role.toLowerCase())) {
      return false;
    }

    // Check if message has content property
    if (!("content" in msg)) {
      return false;
    }

    // Anthropic content can be:
    // 1. string (simple text message)
    // 2. array of content blocks (text blocks, image blocks, etc.)
    if (msg.content !== null && msg.content !== undefined) {
      const isString = typeof msg.content === "string";
      const isArray = Array.isArray(msg.content);

      if (!isString && !isArray) {
        return false;
      }

      // If it's an array, validate it's an array of content blocks
      if (isArray) {
        const contentBlocks = msg.content as unknown[];
        if (contentBlocks.length === 0) {
          return false;
        }
        // Basic validation: each block should be an object with a type
        const allBlocksValid = contentBlocks.every(
          (block) => block && typeof block === "object" && 
          (block as Record<string, unknown>).type,
        );
        if (!allBlocksValid) {
          return false;
        }
      }
    }

    return true;
  });
};

/**
 * Generic function to check if a span has valid messages format
 * Checks both OpenAI and Anthropic formats
 */
export const hasValidMessagesFormat = (span: Span): boolean => {
  return hasValidOpenAIMessagesFormat(span) || hasValidAnthropicMessagesFormat(span);
};

/**
 * Extracts text content from a message content field
 * Handles both string content and array of content parts/blocks
 */
const extractTextContent = (
  content: string | unknown[] | null | undefined,
): string => {
  if (content === null || content === undefined) {
    return "";
  }

  if (typeof content === "string") {
    return content;
  }

  if (Array.isArray(content)) {
    // Extract text from content parts/blocks array
    // Example OpenAI: [{ type: 'text', text: 'Hello' }, { type: 'image_url', image_url: {...} }]
    // Example Anthropic: [{ type: 'text', text: 'Hello' }, { type: 'image', source: {...} }]
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
 * Normalizes role names from Anthropic to standard LLM_MESSAGE_ROLE
 */
const normalizeAnthropicRole = (role: string): LLM_MESSAGE_ROLE => {
  const normalizedRole = role.toLowerCase();
  
  switch (normalizedRole) {
    case "human":
      return LLM_MESSAGE_ROLE.user;
    case "ai":
      return LLM_MESSAGE_ROLE.assistant;
    case "system":
      return LLM_MESSAGE_ROLE.system;
    case "user":
      return LLM_MESSAGE_ROLE.user;
    case "assistant":
      return LLM_MESSAGE_ROLE.assistant;
    default:
      // Fallback to user for unknown roles
      return LLM_MESSAGE_ROLE.user;
  }
};

/**
 * Converts span input messages to LLMMessage format
 * Works for both OpenAI and Anthropic message formats
 */
export const convertSpanMessagesToLLMMessages = (span: Span): LLMMessage[] => {
  // Check format once and cache the results
  const isOpenAIFormat = hasValidOpenAIMessagesFormat(span);
  const isAnthropicFormat = !isOpenAIFormat && hasValidAnthropicMessagesFormat(span);
  
  if (!isOpenAIFormat && !isAnthropicFormat) {
    return [];
  }

  const input = span.input as Record<string, unknown>;
  const messages = input.messages as Array<{
    role: string;
    content: string | unknown[] | null | undefined;
  }>;

  return messages.map((message) => ({
    id: generateRandomString(),
    role: isAnthropicFormat 
      ? normalizeAnthropicRole(message.role)
      : message.role as LLM_MESSAGE_ROLE,
    content: extractTextContent(message.content),
  }));
};