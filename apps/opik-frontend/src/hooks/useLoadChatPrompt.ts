import { useEffect, useMemo, useRef } from "react";
import { AxiosError } from "axios";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import { generateDefaultLLMPromptMessage } from "@/lib/llm";
import { chatTemplatesEqual } from "@/lib/chatTemplate";
import { PromptWithLatestVersion } from "@/types/prompts";

export interface UseLoadChatPromptOptions {
  selectedChatPromptId: string | undefined;
  selectedChatPromptVersionId?: string;
  messages: LLMMessage[];
  onMessagesLoaded: (messages: LLMMessage[], promptName: string) => void;
  /**
   * Fired when the loaded prompt is reported as missing by the backend (404),
   * typically after it was deleted from the library. Callers should clear the
   * loaded references from their state so the UI stops showing the prompt as
   * if it still exists.
   */
  onPromptUnavailable?: () => void;
  skipInitialLoad?: boolean;
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
  selectedChatPromptVersionId,
  messages,
  onMessagesLoaded,
  onPromptUnavailable,
  skipInitialLoad = false,
}: UseLoadChatPromptOptions): UseLoadChatPromptReturn => {
  const skippedRef = useRef(false);
  const loadedChatPromptRef = useRef<string | null>(null);

  const {
    data: chatPromptData,
    isSuccess: chatPromptDataLoaded,
    error: chatPromptError,
  } = usePromptById(
    {
      promptId: selectedChatPromptId!,
    },
    {
      enabled: !!selectedChatPromptId,
      // Don't keep retrying a definitively-missing prompt — the playground
      // needs the 404 to surface promptly so it can detach the deleted prompt.
      retry: (failureCount, error) => {
        if (error instanceof AxiosError && error.response?.status === 404) {
          return false;
        }
        return failureCount < 3;
      },
    },
  );

  // When the selected prompt is reported as 404, tell the caller so it can
  // clear the loaded references from playground state.
  useEffect(() => {
    if (
      selectedChatPromptId &&
      chatPromptError instanceof AxiosError &&
      chatPromptError.response?.status === 404
    ) {
      onPromptUnavailable?.();
    }
  }, [selectedChatPromptId, chatPromptError, onPromptUnavailable]);

  const effectiveVersionId =
    selectedChatPromptVersionId || chatPromptData?.latest_version?.id || "";

  const {
    data: chatPromptVersionData,
    isSuccess: chatPromptVersionDataLoaded,
  } = usePromptVersionById(
    {
      versionId: effectiveVersionId,
    },
    {
      enabled: !!effectiveVersionId && chatPromptDataLoaded,
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

    // A duplicated variant still points at the version it was copied from,
    // so once another variant saves these messages as the latest version,
    // this one is saved too.
    const savedTemplates = [
      chatPromptVersionData.template,
      chatPromptData.latest_version?.template,
    ];

    return !savedTemplates.some(
      (template) =>
        template && chatTemplatesEqual(chatPromptTemplate, template),
    );
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
      // Skip the first load for duplicated prompts that already have messages.
      // We still mark the key as loaded so the hook won't re-trigger and
      // overwrite the duplicated messages with the saved library version.
      if (skipInitialLoad && !skippedRef.current) {
        skippedRef.current = true;
        loadedChatPromptRef.current = chatPromptKey;
        return;
      }

      const fallbackToSingleUserMessage = (content: string) => {
        onMessagesLoaded(
          [
            generateDefaultLLMPromptMessage({
              role: LLM_MESSAGE_ROLE.user,
              content,
            }),
          ],
          chatPromptData.name,
        );
        loadedChatPromptRef.current = chatPromptKey;
      };

      let parsed: unknown;
      try {
        parsed = JSON.parse(chatPromptVersionData.template);
      } catch {
        fallbackToSingleUserMessage(chatPromptVersionData.template);
        return;
      }

      if (!Array.isArray(parsed)) {
        fallbackToSingleUserMessage(
          typeof parsed === "string" ? parsed : chatPromptVersionData.template,
        );
        return;
      }

      const newMessages: LLMMessage[] = parsed.map(
        (msg: { role: string; content: unknown }) =>
          generateDefaultLLMPromptMessage({
            role: msg.role as LLM_MESSAGE_ROLE,
            content: msg.content as LLMMessage["content"],
          }),
      );

      onMessagesLoaded(newMessages, chatPromptData.name);
      loadedChatPromptRef.current = chatPromptKey;
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
    skipInitialLoad,
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
