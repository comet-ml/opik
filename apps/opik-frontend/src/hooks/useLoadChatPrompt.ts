import { useEffect, useMemo, useRef } from "react";
import isEqual from "fast-deep-equal";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { PromptWithLatestVersion } from "@/types/prompts";

export interface UseLoadChatPromptOptions {
  selectedChatPromptId: string | undefined;
  messages: LLMMessage[];
  onMessagesLoaded: (messages: LLMMessage[], promptName: string) => void;
}

export interface UseLoadChatPromptReturn {
  chatPromptData: PromptWithLatestVersion | undefined;
  chatPromptDataLoaded: boolean;
  chatPromptVersionData: ReturnType<typeof usePromptVersionById>["data"];
  chatPromptVersionDataLoaded: boolean;
  loadedChatPromptRef: React.MutableRefObject<string | null>;
  chatPromptTemplate: string;
  hasUnsavedChatPromptChanges: boolean;
}

const useLoadChatPrompt = ({
  selectedChatPromptId,
  messages,
  onMessagesLoaded,
}: UseLoadChatPromptOptions): UseLoadChatPromptReturn => {
  const loadedChatPromptRef = useRef<string | null>(null);

  const { data: chatPromptData, isSuccess: chatPromptDataLoaded } =
    usePromptById(
      {
        promptId: selectedChatPromptId!,
      },
      {
        enabled: !!selectedChatPromptId,
      },
    );

  const {
    data: chatPromptVersionData,
    isSuccess: chatPromptVersionDataLoaded,
  } = usePromptVersionById(
    {
      versionId: chatPromptData?.latest_version?.id || "",
    },
    {
      enabled: !!chatPromptData?.latest_version?.id && chatPromptDataLoaded,
    },
  );

  const chatPromptTemplate = useMemo(
    () =>
      JSON.stringify(
        messages.map((msg) => ({ role: msg.role, content: msg.content })),
      ),
    [messages],
  );

  const hasUnsavedChatPromptChanges = useMemo(() => {
    const hasContent = messages.length > 0;

    if (!hasContent || !selectedChatPromptId) {
      return false;
    }

    if (!chatPromptData || chatPromptData.id !== selectedChatPromptId) {
      return false;
    }

    if (!chatPromptVersionData?.template) {
      return false;
    }

    // Parse both templates as objects to compare semantically, not by string formatting
    // IMPORTANT: Only compare role and content, ignore text prompt metadata fields
    try {
      const currentTemplate = JSON.parse(chatPromptTemplate);
      const loadedTemplate = JSON.parse(chatPromptVersionData.template);

      const normalizeTemplate = (
        template: Array<{
          role: string;
          content: unknown;
          promptId?: string;
          promptVersionId?: string;
        }>,
      ) => template.map(({ role, content }) => ({ role, content }));

      const normalizedCurrent = normalizeTemplate(currentTemplate);
      const normalizedLoaded = normalizeTemplate(loadedTemplate);

      return !isEqual(normalizedCurrent, normalizedLoaded);
    } catch {
      return !isEqual(chatPromptTemplate, chatPromptVersionData.template);
    }
  }, [
    selectedChatPromptId,
    chatPromptData,
    chatPromptVersionData,
    chatPromptTemplate,
    messages.length,
  ]);

  // effect to populate messages when chat prompt data is loaded
  useEffect(() => {
    // create a unique key for this chat prompt load (prompt ID + version ID)
    const chatPromptKey =
      selectedChatPromptId && chatPromptVersionData
        ? `${selectedChatPromptId}-${chatPromptVersionData.id}`
        : null;

    if (
      chatPromptVersionData?.template &&
      selectedChatPromptId &&
      chatPromptData &&
      chatPromptVersionDataLoaded &&
      chatPromptKey &&
      loadedChatPromptRef.current !== chatPromptKey // prevent duplicate loads
    ) {
      try {
        // Mark this chat prompt as loaded to prevent race conditions
        loadedChatPromptRef.current = chatPromptKey;

        const parsedMessages = JSON.parse(chatPromptVersionData.template);

        const newMessages: LLMMessage[] = parsedMessages.map(
          (msg: { role: string; content: unknown }) =>
            generateDefaultLLMPromptMessage({
              role: msg.role as LLM_MESSAGE_ROLE,
              content: msg.content as LLMMessage["content"],
            }),
        );

        onMessagesLoaded(newMessages, chatPromptData.name);
      } catch (error) {
        console.error("Failed to parse chat prompt:", error);
      }
    }

    // reset the ref when chat prompt is deselected
    if (!selectedChatPromptId) {
      loadedChatPromptRef.current = null;
    }
  }, [
    chatPromptVersionData,
    selectedChatPromptId,
    chatPromptData,
    chatPromptVersionDataLoaded,
    onMessagesLoaded,
  ]);

  return {
    chatPromptData,
    chatPromptDataLoaded,
    chatPromptVersionData,
    chatPromptVersionDataLoaded,
    loadedChatPromptRef,
    chatPromptTemplate,
    hasUnsavedChatPromptChanges,
  };
};

export default useLoadChatPrompt;
