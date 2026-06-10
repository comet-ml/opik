import { useCallback } from "react";
import isEqual from "fast-deep-equal";

import {
  PlaygroundPromptType,
  PromptLibraryMetadata,
} from "@/types/playground";
import { PromptVersion } from "@/types/prompts";
import { parsePromptVersionContent } from "@/lib/llm";
import { useFetchPrompt } from "@/api/prompts/usePromptById";
import { useFetchPromptVersion } from "@/api/prompts/usePromptVersionById";
import { serializeChatTemplate, chatTemplatesEqual } from "@/lib/chatTemplate";
import { buildPromptLibraryMetadata } from "@/api/playground/promptLinkage";

export function useHydratePromptMetadata() {
  const fetchPrompt = useFetchPrompt();
  const fetchPromptVersion = useFetchPromptVersion();

  return useCallback(
    async (
      prompt: PlaygroundPromptType,
    ): Promise<PromptLibraryMetadata | undefined> => {
      // Loaded from a CHAT prompt in the library
      const chatPromptId = prompt.loadedChatPromptId;
      if (chatPromptId) {
        const currentMessages = prompt.messages.map((m) => ({
          role: m.role,
          content: m.content,
        }));
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

          // Keep the library link even when the user edited the prompt after
          // loading it — mark it modified rather than silently dropping the
          // reference (OPIK-6838 #4). Truly ad-hoc prompts never reach here
          // because they have no loadedChatPromptId.
          const modified = !chatTemplatesEqual(
            serializeChatTemplate(currentMessages),
            sourceVersion.template,
          );

          return buildPromptLibraryMetadata(
            promptData,
            {
              id: sourceVersion.id,
              template: sourceVersion.template,
              commit: sourceVersion.commit,
              metadata: sourceVersion.metadata,
            },
            modified,
          );
        } catch {
          return undefined;
        }
      }

      // For TEXT prompts - check message-level library link. Prefer an exact
      // (unmodified) match; otherwise keep the first edited library-linked
      // message and mark it modified (OPIK-6838 #4) rather than dropping it.
      let fallback: PromptLibraryMetadata | undefined;
      for (const message of prompt.messages) {
        if (!message.promptId) continue;

        try {
          const promptData = await fetchPrompt({ promptId: message.promptId });

          if (!promptData?.latest_version) continue;

          const libraryContent = parsePromptVersionContent(
            promptData.latest_version,
          );
          const modified = !isEqual(message.content, libraryContent);
          const metadata = buildPromptLibraryMetadata(
            promptData,
            {
              id: promptData.latest_version.id,
              template: promptData.latest_version.template,
              commit: promptData.latest_version.commit,
              metadata: promptData.latest_version.metadata,
            },
            modified,
          );

          if (!modified) return metadata;
          fallback ??= metadata;
        } catch {
          continue;
        }
      }

      return fallback;
    },
    [fetchPrompt, fetchPromptVersion],
  );
}
