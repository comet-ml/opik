import { useCallback } from "react";
import { useQueryClient } from "@tanstack/react-query";
import isEqual from "fast-deep-equal";

import { PlaygroundPromptType } from "@/types/playground";
import { PromptWithLatestVersion, PromptVersion } from "@/types/prompts";
import { parsePromptVersionContent } from "@/lib/llm";

export interface PromptLibraryMetadata {
  name: string;
  id: string;
  version: {
    template: unknown;
    commit?: string;
    id: string;
    metadata?: object;
  };
}

type NormalizedMessage = { role: string; content: unknown };

const normalizeForComparison = (
  messages: Array<{ role: string; content: unknown }>,
): NormalizedMessage[] =>
  messages.map(({ role, content }) => ({ role, content }));

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
  promptData: { name: string; id: string },
  versionData: VersionData,
): PromptLibraryMetadata => ({
  name: promptData.name,
  id: promptData.id,
  version: {
    template: parseTemplateJson(versionData.template),
    id: versionData.id,
    ...(versionData.commit && { commit: versionData.commit }),
    ...(versionData.metadata && { metadata: versionData.metadata }),
  },
});

export function useHydratePromptMetadata() {
  const queryClient = useQueryClient();

  return useCallback(
    async (
      prompt: PlaygroundPromptType,
    ): Promise<PromptLibraryMetadata | undefined> => {
      // For CHAT prompts - check prompt-level library link
      const chatPromptId = prompt.loadedChatPromptId;
      if (chatPromptId) {
        try {
          // Use getQueryData to check if data is already cached
          // This avoids network requests during execution
          const promptData = queryClient.getQueryData<PromptWithLatestVersion>([
            "prompt",
            { promptId: chatPromptId },
          ]);

          if (!promptData?.latest_version?.id) return undefined;

          // Try to get the version data from cache
          const versionData = queryClient.getQueryData<PromptVersion>([
            "prompt-version",
            { versionId: promptData.latest_version.id },
          ]);

          // If we have version data, use it for comparison (more accurate)
          const templateToCompare =
            versionData?.template ?? promptData.latest_version.template;

          if (!templateToCompare) return undefined;

          // Parse the library template for comparison
          let libraryMessages: NormalizedMessage[];
          try {
            const parsed = JSON.parse(templateToCompare);
            libraryMessages = normalizeForComparison(parsed);
          } catch {
            return undefined;
          }

          // Compare current messages to library template
          const currentMessages = normalizeForComparison(
            prompt.messages.map((m) => ({ role: m.role, content: m.content })),
          );

          if (!isEqual(currentMessages, libraryMessages)) {
            return undefined; // Prompt was edited
          }

          return buildMetadata(promptData, {
            id: versionData?.id ?? promptData.latest_version.id,
            template:
              versionData?.template ?? promptData.latest_version.template,
            commit: versionData?.commit ?? promptData.latest_version.commit,
            metadata:
              versionData?.metadata ?? promptData.latest_version.metadata,
          });
        } catch {
          return undefined;
        }
      }

      // For TEXT prompts - check message-level library link
      for (const message of prompt.messages) {
        if (!message.promptId) continue;

        try {
          const promptData = queryClient.getQueryData<PromptWithLatestVersion>([
            "prompt",
            { promptId: message.promptId },
          ]);

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
    [queryClient],
  );
}
