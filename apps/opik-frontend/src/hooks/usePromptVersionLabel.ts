import { useMemo } from "react";

import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";

/**
 * Compute the human-facing label for a specific prompt version, using the
 * backend-persisted version_number so it stays correct even after older
 * versions are deleted (positional "v{n}" labels shift when that happens).
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
      const version = data.content.find((v) => v.id === versionId);
      if (version) return version.version_number ?? version.commit;
    }
    return fallbackVersionCount && fallbackVersionCount > 0
      ? `v${fallbackVersionCount}`
      : undefined;
  }, [versionId, data, fallbackVersionCount]);
};

export default usePromptVersionLabel;
