import isEqual from "fast-deep-equal";
import { AxiosError } from "axios";

import { LLMMessage } from "@/types/llm";
import usePromptById from "@/api/prompts/usePromptById";
import usePromptVersionById from "@/api/prompts/usePromptVersionById";
import { parsePromptVersionContent, toContentForRole } from "@/lib/llm";

const useMessageTextPrompt = (message?: LLMMessage) => {
  const promptId = message?.promptId;
  const promptVersionId = message?.promptVersionId;

  const { data: promptData, error: promptError } = usePromptById(
    { promptId: promptId! },
    {
      enabled: !!promptId,
      // Don't retry a definitively-missing prompt; the 404 needs to surface
      // promptly so the message can be detached from it.
      retry: (failureCount, error) => {
        if (error instanceof AxiosError && error.response?.status === 404) {
          return false;
        }
        return failureCount < 3;
      },
    },
  );
  // When a specific version is loaded, the dirty check needs that version's
  // content — not `latest_version`, which would flag everything as unsaved
  // whenever the user picked an older version.
  const { data: loadedVersionData } = usePromptVersionById(
    { versionId: promptVersionId! },
    { enabled: !!promptVersionId },
  );

  const baselineVersion =
    promptVersionId && loadedVersionData?.id === promptVersionId
      ? loadedVersionData
      : promptData?.latest_version;
  const hasUnsavedChanges = Boolean(
    message &&
      promptId &&
      promptData?.id === promptId &&
      !isEqual(
        message.content,
        toContentForRole(
          parsePromptVersionContent(baselineVersion),
          message.role,
        ).content,
      ),
  );

  return { promptData, promptError, loadedVersionData, hasUnsavedChanges };
};

export default useMessageTextPrompt;
