import { useCallback } from "react";
import isEqual from "fast-deep-equal";

import {
  PlaygroundPromptType,
  PromptLibraryMetadata,
} from "@/types/playground";
import { PROMPT_TEMPLATE_STRUCTURE, PromptVersion } from "@/types/prompts";
import { parsePromptVersionContent } from "@/lib/llm";
import { useFetchPrompt } from "@/api/prompts/usePromptById";
import { useFetchPromptVersion } from "@/api/prompts/usePromptVersionById";
import { serializeChatTemplate, chatTemplatesEqual } from "@/lib/chatTemplate";

const parseTemplateJson = (template: string | undefined): unknown => {
  if (!template) return null;
  try {
    return JSON.parse(template);
  } catch {
    return template;
  }
};

interface VersionData {
  id: string;
  template?: string;
  commit?: string;
  metadata?: object;
}

const buildMetadata = (
  promptData: { name: string; id: string; template_structure?: string },
  versionData: VersionData,
): PromptLibraryMetadata => ({
  name: promptData.name,
  id: promptData.id,
  template_structure:
    (promptData.template_structure as PROMPT_TEMPLATE_STRUCTURE) ??
    PROMPT_TEMPLATE_STRUCTURE.TEXT,
  version: {
    template: parseTemplateJson(versionData.template),
    id: versionData.id,
    ...(versionData.commit && { commit: versionData.commit }),
    ...(versionData.metadata && { metadata: versionData.metadata }),
  },
});

export function useHydratePromptMetadata() {
  const fetchPrompt = useFetchPrompt();
  const fetchPromptVersion = useFetchPromptVersion();

  return useCallback(
    async (
      prompt: PlaygroundPromptType,
    ): Promise<PromptLibraryMetadata | undefined> => {
      const currentMessages = prompt.messages.map((m) => ({
        role: m.role,
        content: m.content,
      }));

      // Loaded from a CHAT prompt in the library
      const chatPromptId = prompt.loadedChatPromptId;
      if (chatPromptId) {
        try {
          const promptData = await fetchPrompt({ promptId: chatPromptId });
          const explicitVersionId = prompt.loadedChatPromptVersionId;
          const targetVersionId =
            explicitVersionId ?? promptData?.latest_version?.id;
          if (!targetVersionId) return undefined;

          let versionData: PromptVersion | undefined;
          try {
            versionData = await fetchPromptVersion({
              versionId: targetVersionId,
            });
          } catch {
            // Only fall back to latest_version when no explicit version was
            // requested. Otherwise we'd compare against the wrong version
            // and silently drop the library link.
          }

          // When an explicit version was selected but we couldn't fetch it,
          // there's no safe anchor — bail rather than mismatch.
          if (explicitVersionId && !versionData) return undefined;

          const sourceVersion = versionData ?? promptData?.latest_version;
          if (!sourceVersion?.template) return undefined;

          if (
            !chatTemplatesEqual(
              serializeChatTemplate(currentMessages),
              sourceVersion.template,
            )
          )
            return undefined;

          return buildMetadata(promptData, {
            id: sourceVersion.id,
            template: sourceVersion.template,
            commit: sourceVersion.commit,
            metadata: sourceVersion.metadata,
          });
        } catch {
          return undefined;
        }
      }

      // For TEXT prompts - check message-level library link
      for (const message of prompt.messages) {
        if (!message.promptId) continue;

        try {
          const promptData = await fetchPrompt({ promptId: message.promptId });

          if (!promptData?.latest_version) continue;

          // Parse the library content for comparison
          const libraryContent = parsePromptVersionContent(
            promptData.latest_version,
          );
          if (!isEqual(message.content, libraryContent)) continue; // Edited

          return buildMetadata(promptData, {
            id: promptData.latest_version.id,
            template: promptData.latest_version.template,
            commit: promptData.latest_version.commit,
            metadata: promptData.latest_version.metadata,
          });
        } catch {
          continue;
        }
      }

      return undefined;
    },
    [fetchPrompt, fetchPromptVersion],
  );
}
