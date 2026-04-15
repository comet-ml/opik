import isEqual from "fast-deep-equal";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";

export const serializeChatTemplate = (
  messages: Array<{ role: string; content: unknown }>,
): string =>
  JSON.stringify(messages.map(({ role, content }) => ({ role, content })));

export const chatTemplatesEqual = (a: string, b: string): boolean => {
  try {
    const normalize = (raw: string) =>
      JSON.parse(raw).map(
        ({ role, content }: { role: string; content: unknown }) => ({
          role,
          content,
        }),
      );
    return isEqual(normalize(a), normalize(b));
  } catch {
    return a === b;
  }
};

export const parseChatTemplateToMessages = (template: string): LLMMessage[] => {
  const parsed = JSON.parse(template) as Array<{
    role: string;
    content: unknown;
  }>;
  return parsed.map((msg) =>
    generateDefaultLLMPromptMessage({
      role: msg.role as LLM_MESSAGE_ROLE,
      content: msg.content as LLMMessage["content"],
    }),
  );
};
