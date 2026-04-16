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
import { useFetchPromptByCommit } from "@/api/prompts/usePromptByCommit";
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
  const fetchPromptByCommit = useFetchPromptByCommit();

  return useCallback(
    async (
      prompt: PlaygroundPromptType,
    ): Promise<PromptLibraryMetadata | undefined> => {
      const currentMessages = prompt.messages.map((m) => ({
        role: m.role,
        content: m.content,
      }));

      // Loaded from an agent configuration blueprint
      const blueprintRef = prompt.loadedBlueprintRef;
      if (blueprintRef) {
        try {
          const commitData = await fetchPromptByCommit({
            commitId: blueprintRef.commitId,
          });
          const version = commitData.requested_version;
          if (!version?.template) return undefined;
          if (
            !chatTemplatesEqual(
              serializeChatTemplate(currentMessages),
              version.template,
            )
          )
            return undefined;

          return buildMetadata(
            {
              name: commitData.name,
              id: commitData.id,
              template_structure: commitData.template_structure,
            },
            {
              id: version.id,
              template: version.template,
              commit: version.commit,
              metadata: version.metadata ?? undefined,
            },
          );
        } catch {
          return undefined;
        }
      }

      // Loaded from a CHAT prompt in the library (legacy path)
      const chatPromptId = prompt.loadedChatPromptId;
      if (chatPromptId) {
        try {
          const promptData = await fetchPrompt({ promptId: chatPromptId });
          if (!promptData?.latest_version?.id) return undefined;

          let versionData: PromptVersion | undefined;
          try {
            versionData = await fetchPromptVersion({
              versionId: promptData.latest_version.id,
            });
          } catch {
            // Fall back to latest_version embedded in the prompt response
          }

          const templateToCompare =
            versionData?.template ?? promptData.latest_version.template;
          if (!templateToCompare) return undefined;
          if (
            !chatTemplatesEqual(
              serializeChatTemplate(currentMessages),
              templateToCompare,
            )
          )
            return undefined;

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
    [fetchPrompt, fetchPromptVersion, fetchPromptByCommit],
  );
}
