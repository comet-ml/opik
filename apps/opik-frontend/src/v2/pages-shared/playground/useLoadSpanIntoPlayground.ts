import { useCallback } from "react";

import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { getSpanPlaygroundMessages } from "@/v2/pages-shared/playground/spanPlaygroundMessages";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";
import { Span } from "@/types/traces";

/**
 * Loads the input messages of a logged LLM span into the Playground as a chat
 * prompt, on the span's model when a configured provider serves it. Thin
 * wrapper over `useLoadPlayground`, like `useLoadPromptIntoPlayground`.
 */
function useLoadSpanIntoPlayground() {
  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const loadSpan = useCallback(
    (span: Span) => {
      const messages = getSpanPlaygroundMessages(span);
      if (messages.length === 0) return;

      loadPlayground({
        promptContent: JSON.stringify(messages),
        templateStructure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
        // A logged model name is free-form; useLoadPlayground keeps it only
        // when it resolves to a configured provider.
        preferredModel: span.model as PROVIDER_MODEL_TYPE | undefined,
      });
    },
    [loadPlayground],
  );

  return { loadSpan, isPlaygroundEmpty, isPendingProviderKeys };
}

export default useLoadSpanIntoPlayground;
