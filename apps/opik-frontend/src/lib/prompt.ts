import mustache from "mustache";
import isString from "lodash/isString";
import isArray from "lodash/isArray";
import isObject from "lodash/isObject";

import { LLM_MESSAGE_ROLE_NAME_MAP } from "@/constants/llm";

export const getPromptMustacheTags = (template: string) => {
  const parsedTemplate = mustache.parse(template);

  return parsedTemplate
    .filter(([type]) => type === "name")
    .map(([, name]) => name);
};

export const safelyGetPromptMustacheTags = (template: string) => {
  try {
    const parsedTemplate = mustache.parse(template);

    return parsedTemplate
      .filter(([type]) => type === "name" || type === "#" || type === "^")
      .map(([, name]) => name);
  } catch (error) {
    return false;
  }
};

export type OpenAIMessage = {
  role: string;
  content:
    | string
    | Array<{ type: string; text?: string; [key: string]: unknown }>;
};

/**
 * Extracts text content from OpenAI message format.
 * Handles both string content and array content (extracts text from {type: "text", text: "..."} items).
 */
export const extractMessageContent = (
  content:
    | string
    | Array<{ type: string; text?: string; [key: string]: unknown }>
    | unknown,
): string => {
  if (isString(content)) {
    return content;
  }

  if (isArray(content)) {
    const textParts: string[] = [];
    for (const item of content) {
      if (
        isObject(item) &&
        "type" in item &&
        item.type === "text" &&
        "text" in item &&
        isString(item.text)
      ) {
        textParts.push(item.text);
      }
    }
    return textParts.join("\n");
  }

  return "";
};

/**
 * Validates if an array contains valid OpenAI message objects.
 */
export const isValidOpenAIMessages = (
  messages: unknown[],
): messages is OpenAIMessage[] => {
  return messages.every(
    (msg: unknown) =>
      isObject(msg) &&
      "role" in msg &&
      isString((msg as { role: unknown }).role) &&
      "content" in msg,
  );
};

/**
 * Extracts OpenAI messages from various data formats.
 * Handles both array format and object with messages property.
 */
export const extractOpenAIMessages = (
  data: unknown,
): OpenAIMessage[] | null => {
  // Check if it's an array of messages (OpenAI format)
  if (isArray(data) && isValidOpenAIMessages(data)) {
    return data;
  }

  // Check if it's an object with a messages array
  if (isObject(data) && "messages" in data) {
    const promptObj = data as { messages?: unknown };
    if (
      isArray(promptObj.messages) &&
      isValidOpenAIMessages(promptObj.messages)
    ) {
      return promptObj.messages;
    }
  }

  return null;
};

/**
 * Formats OpenAI messages as readable text.
 */
export const formatMessagesAsText = (messages: OpenAIMessage[]): string => {
  return messages
    .map((msg) => {
      const roleName =
        LLM_MESSAGE_ROLE_NAME_MAP[
          msg.role as keyof typeof LLM_MESSAGE_ROLE_NAME_MAP
        ] || msg.role;
      const content = extractMessageContent(msg.content);
      return `${roleName}: ${content}`;
    })
    .join("\n\n");
};
