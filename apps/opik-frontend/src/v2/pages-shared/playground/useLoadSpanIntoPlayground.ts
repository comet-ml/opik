import { useCallback } from "react";

import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { getSpanPlaygroundMessages } from "@/v2/pages-shared/playground/spanPlaygroundMessages";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { PROVIDER_MODEL_TYPE, PROVIDER_TYPE } from "@/types/providers";
import { Span } from "@/types/traces";

// The SDKs log the Google providers under their own names. Other logged names,
// including those of spans the Playground logged itself, match a provider key.
const LOGGED_PROVIDER_KEYS: Record<string, PROVIDER_TYPE> = {
  google_ai: PROVIDER_TYPE.GEMINI,
  google_vertexai: PROVIDER_TYPE.VERTEX_AI,
};

const getProviderKey = (loggedProvider?: string) =>
  (loggedProvider && LOGGED_PROVIDER_KEYS[loggedProvider]) || loggedProvider;

/**
 * Loads the input messages of a logged LLM span into the Playground as a chat
 * prompt, on the span's model when a configured provider offers it. Thin
 * wrapper over `useLoadPlayground`, like `useLoadPromptIntoPlayground`.
 */
function useLoadSpanIntoPlayground() {
  const {
    loadPlayground,
    isPlaygroundEmpty,
    isPendingProviderKeys,
    isPendingModels,
  } = useLoadPlayground();

  const loadSpan = useCallback(
    (span: Span) => {
      const messages = getSpanPlaygroundMessages(span);
      if (messages.length === 0) return;

      loadPlayground({
        promptContent: JSON.stringify(messages),
        templateStructure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
        // A logged model name is free-form; useLoadPlayground keeps it only
        // when a configured provider offers it in the model dropdown.
        preferredModel: span.model as PROVIDER_MODEL_TYPE | undefined,
        preferredProvider: getProviderKey(span.provider),
      });
    },
    [loadPlayground],
  );

  return {
    loadSpan,
    isPlaygroundEmpty,
    // The span's model can only be matched to a provider once the model
    // catalog has loaded.
    isPending: isPendingProviderKeys || isPendingModels,
  };
}

export default useLoadSpanIntoPlayground;
