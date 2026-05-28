import { useCallback, useEffect, useMemo, useRef, useState } from "react";

import { LLMMessage } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import {
  generateDefaultLLMPromptMessage,
  getNextMessageType,
  parseChatTemplateToLLMMessages,
} from "@/lib/llm";
import {
  chatTemplatesEqual,
  normalizeChatTemplate,
  serializeChatTemplate,
} from "@/lib/chatTemplate";

export type ChatViewMode = "pretty" | "json";

type Args = {
  /**
   * The template the editor should reset to whenever `open` flips from false
   * to true. Both sheets use this to re-seed editor state after a prop change
   * (e.g. picking a different version in EditPromptSheet).
   */
  initialTemplate: string;
  templateStructure: PROMPT_TEMPLATE_STRUCTURE;
  open: boolean;
};

const serializeMessagesForRaw = (messages: LLMMessage[]): string =>
  JSON.stringify(
    messages.map((m) => ({ role: m.role, content: m.content })),
    null,
    2,
  );

/**
 * Owns the prompt-template editor state shared by CreatePromptSheet and
 * EditPromptSheet: text-template, chat messages, Pretty/JSON view mode, raw
 * JSON value, validation flag, and the on-open reset effect.
 */
export function usePromptTemplateEditor({
  initialTemplate,
  templateStructure,
  open,
}: Args) {
  const isChatPrompt = templateStructure === PROMPT_TEMPLATE_STRUCTURE.CHAT;

  const initialMessages = useMemo<LLMMessage[]>(() => {
    if (!isChatPrompt) return [];
    const parsed = parseChatTemplateToLLMMessages(initialTemplate);
    return parsed.length > 0 ? parsed : [generateDefaultLLMPromptMessage()];
  }, [isChatPrompt, initialTemplate]);

  const [template, setTemplate] = useState(initialTemplate);
  const [messages, setMessages] = useState<LLMMessage[]>(initialMessages);
  const [chatViewMode, setChatViewMode] = useState<ChatViewMode>("pretty");
  const [rawJsonValue, setRawJsonValue] = useState(() =>
    isChatPrompt ? normalizeChatTemplate(initialTemplate) : "",
  );
  const [isRawJsonValid, setIsRawJsonValid] = useState(true);

  // Track latest props so the reset effect doesn't depend on every prop change
  // (would re-fire on each rerender) but still reads the freshest value.
  const latestRef = useRef({ initialTemplate, initialMessages });
  latestRef.current = { initialTemplate, initialMessages };
  useEffect(() => {
    if (!open) return;
    const next = latestRef.current;
    setTemplate(next.initialTemplate);
    setMessages(next.initialMessages);
    setRawJsonValue(
      isChatPrompt ? normalizeChatTemplate(next.initialTemplate) : "",
    );
    setIsRawJsonValid(true);
    setChatViewMode("pretty");
  }, [open, isChatPrompt]);

  const handleSwitchChatView = useCallback(
    (next: ChatViewMode) => {
      if (next === chatViewMode) return;
      if (next === "json") {
        setRawJsonValue(serializeMessagesForRaw(messages));
        setIsRawJsonValid(true);
      }
      setChatViewMode(next);
    },
    [chatViewMode, messages],
  );

  const handleAddMessage = useCallback(() => {
    setMessages((prev) => {
      const last = prev[prev.length - 1];
      const nextRole = last ? getNextMessageType(last) : undefined;
      return [...prev, generateDefaultLLMPromptMessage({ role: nextRole })];
    });
  }, []);

  const copyableRaw = useMemo(
    () =>
      chatViewMode === "json"
        ? rawJsonValue
        : serializeMessagesForRaw(messages),
    [chatViewMode, rawJsonValue, messages],
  );

  const isDirty = useMemo(() => {
    if (isChatPrompt) {
      // Compare against the serialized form of `initialMessages` rather than
      // the raw `initialTemplate` string — when `initialTemplate` is empty
      // (create flow), `initialMessages` falls back to a single default user
      // message. Comparing the serialized messages against an empty string
      // would always be unequal, flagging fresh sheets as dirty.
      return !chatTemplatesEqual(
        serializeChatTemplate(messages),
        serializeChatTemplate(initialMessages),
      );
    }
    return template !== initialTemplate;
  }, [isChatPrompt, messages, initialMessages, template, initialTemplate]);

  const isValid = isChatPrompt
    ? messages.length > 0 && (chatViewMode === "pretty" || isRawJsonValid)
    : (template?.trim().length ?? 0) > 0;

  const serialize = useCallback(() => {
    if (isChatPrompt) return serializeChatTemplate(messages);
    return template;
  }, [isChatPrompt, messages, template]);

  return {
    isChatPrompt,
    template,
    setTemplate,
    messages,
    setMessages,
    chatViewMode,
    onChatViewModeChange: handleSwitchChatView,
    rawJsonValue,
    setRawJsonValue,
    isRawJsonValid,
    setIsRawJsonValid,
    handleAddMessage,
    copyableRaw,
    isDirty,
    isValid,
    serialize,
  };
}

export type PromptTemplateEditorState = ReturnType<
  typeof usePromptTemplateEditor
>;
