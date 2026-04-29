import { useEffect, useMemo, useRef } from "react";

import { LLMMessage } from "@/types/llm";
import { BlueprintPromptRef } from "@/types/playground";
import usePromptByCommit from "@/api/prompts/usePromptByCommit";
import { PromptByCommit } from "@/types/prompts";
import {
  serializeChatTemplate,
  chatTemplatesEqual,
  parseChatTemplateToMessages,
} from "@/lib/chatTemplate";

interface UseLoadBlueprintPromptOptions {
  selectedRef: BlueprintPromptRef | undefined;
  messages: LLMMessage[];
  onMessagesLoaded: (messages: LLMMessage[], promptName: string) => void;
  skipInitialLoad?: boolean;
}

interface UseLoadBlueprintPromptReturn {
  prompt: PromptByCommit | undefined;
  loadedRef: React.MutableRefObject<string | null>;
  template: string;
  hasUnsavedChanges: boolean;
}

const refKey = (ref: BlueprintPromptRef): string =>
  `${ref.blueprintId}-${ref.key}-${ref.commitId}`;

const useLoadBlueprintPrompt = ({
  selectedRef,
  messages,
  onMessagesLoaded,
  skipInitialLoad = false,
}: UseLoadBlueprintPromptOptions): UseLoadBlueprintPromptReturn => {
  const skippedRef = useRef(false);
  const loadedRef = useRef<string | null>(null);

  const { data: prompt } = usePromptByCommit(
    { commitId: selectedRef?.commitId ?? "" },
    { enabled: !!selectedRef?.commitId },
  );

  const versionTemplate = prompt?.requested_version?.template;

  const template = useMemo(() => serializeChatTemplate(messages), [messages]);

  const hasUnsavedChanges = useMemo(() => {
    if (!selectedRef || !versionTemplate || messages.length === 0) return false;
    return !chatTemplatesEqual(template, versionTemplate);
  }, [selectedRef, versionTemplate, template, messages.length]);

  useEffect(() => {
    if (!selectedRef) {
      loadedRef.current = null;
      return;
    }
    if (!prompt || !versionTemplate) return;

    const dedupKey = `${refKey(selectedRef)}-${prompt.requested_version.id}`;
    if (loadedRef.current === dedupKey) return;

    if (skipInitialLoad && !skippedRef.current) {
      skippedRef.current = true;
      loadedRef.current = dedupKey;
      return;
    }

    try {
      onMessagesLoaded(
        parseChatTemplateToMessages(versionTemplate),
        prompt.name,
      );
      loadedRef.current = dedupKey;
    } catch {
      // silently ignore parse failures
    }
  }, [selectedRef, prompt, versionTemplate, onMessagesLoaded, skipInitialLoad]);

  return { prompt, loadedRef, template, hasUnsavedChanges };
};

export default useLoadBlueprintPrompt;
