import { useMemo } from "react";

import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";

/**
 * Compute the human-facing "v{n}" label for a specific prompt version.
 *
 * Versions are labeled by their position when sorted by created_at desc:
 * the oldest is v1, the newest is v{total}. We need the versions list to
 * find that position — version_count on the Prompt object only tells us
 * the total (i.e., the latest's label).
 */
const usePromptVersionLabel = (
  promptId: string | undefined,
  versionId: string | undefined,
  fallbackVersionCount: number | undefined,
): string | undefined => {
  const { data } = usePromptVersionsById(
    {
      promptId: promptId ?? "",
      page: 1,
      size: 100,
      sorting: [{ id: "created_at", desc: true }],
    },
    { enabled: !!promptId && !!versionId, staleTime: 60_000 },
  );

  return useMemo(() => {
    if (versionId && data?.content) {
      const idx = data.content.findIndex((v) => v.id === versionId);
      const total = data.total ?? data.content.length;
      if (idx >= 0 && total > 0) return `v${total - idx}`;
    }
    return fallbackVersionCount && fallbackVersionCount > 0
      ? `v${fallbackVersionCount}`
      : undefined;
  }, [versionId, data, fallbackVersionCount]);
};

export default usePromptVersionLabel;
