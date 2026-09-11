import isEqual from "fast-deep-equal";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";

type ChatTemplateMessage = { role: string; content: unknown };

const projectMessages = (
  messages: ChatTemplateMessage[],
): ChatTemplateMessage[] =>
  messages.map(({ role, content }) => ({ role, content }));

const isChatTemplateShape = (value: unknown): value is ChatTemplateMessage[] =>
  Array.isArray(value) &&
  value.every(
    (m) => m && typeof m === "object" && "role" in m && "content" in m,
  );

export const serializeChatTemplate = (
  messages: ChatTemplateMessage[],
): string => JSON.stringify(projectMessages(messages), null, 2);

// Returns the parsed message array when `raw` is a valid chat template,
// otherwise null. Caller can use the truthy check as a chat detection.
export const parseChatTemplate = (
  raw: string,
): ChatTemplateMessage[] | null => {
  try {
    const parsed = JSON.parse(raw);
    return isChatTemplateShape(parsed) ? parsed : null;
  } catch {
    return null;
  }
};

// Re-serializes a chat template into a stable pretty-printed form so two
// equivalent payloads stored with different whitespace diff as identical and
// render readably. Falls back to the raw input when it doesn't look like a
// chat template.
export const normalizeChatTemplate = (raw: string): string => {
  try {
    const parsed = JSON.parse(raw);
    if (!isChatTemplateShape(parsed)) return raw;
    return JSON.stringify(projectMessages(parsed), null, 2);
  } catch {
    return raw;
  }
};

export const chatTemplatesEqual = (a: string, b: string): boolean => {
  try {
    return isEqual(
      projectMessages(JSON.parse(a) as ChatTemplateMessage[]),
      projectMessages(JSON.parse(b) as ChatTemplateMessage[]),
    );
  } catch {
    return a === b;
  }
};

export const parseChatTemplateToMessages = (template: string): LLMMessage[] => {
  const parsed = JSON.parse(template) as ChatTemplateMessage[];
  return parsed.map((msg) =>
    generateDefaultLLMPromptMessage({
      role: msg.role as LLM_MESSAGE_ROLE,
      content: msg.content as LLMMessage["content"],
    }),
  );
};
