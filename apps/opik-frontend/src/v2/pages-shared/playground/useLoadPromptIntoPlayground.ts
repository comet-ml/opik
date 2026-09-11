import { useCallback } from "react";

import useLoadPlayground from "@/v2/pages-shared/playground/useLoadPlayground";
import { parsePromptVersionContent } from "@/lib/llm";
import { PromptVersion, PromptWithLatestVersion } from "@/types/prompts";

type PromptShape = Pick<
  PromptWithLatestVersion,
  "id" | "template_structure" | "latest_version"
>;

/**
 * Thin wrapper over `useLoadPlayground` so the Playground call shape used by
 * "Try in Playground" / PromptTab / etc. stays in one place — same fields, same
 * fallback to `prompt.latest_version`, same `parsePromptVersionContent`
 * extraction.
 */
function useLoadPromptIntoPlayground() {
  const { loadPlayground, isPlaygroundEmpty, isPendingProviderKeys } =
    useLoadPlayground();

  const loadPrompt = useCallback(
    ({
      prompt,
      version,
    }: {
      prompt: PromptShape;
      version?: PromptVersion | null;
    }) => {
      const source = version ?? prompt.latest_version;
      loadPlayground({
        promptContent: parsePromptVersionContent(source),
        promptId: prompt.id,
        promptVersionId: source?.id,
        templateStructure: prompt.template_structure,
      });
    },
    [loadPlayground],
  );

  return { loadPrompt, isPlaygroundEmpty, isPendingProviderKeys };
}

export default useLoadPromptIntoPlayground;
