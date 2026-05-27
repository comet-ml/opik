import { useMemo } from "react";

import usePromptVersionsById from "@/api/prompts/usePromptVersionsById";
import { PromptVersion } from "@/types/prompts";
import { pickHighestStage } from "@/utils/version-stages";

export type PromptVersionDescriptor = {
  version: PromptVersion;
  index: number;
  label: string;
  stage: string | undefined;
};

type Options = {
  enabled?: boolean;
  staleTime?: number;
  /**
   * Result page size. Default of 100 matches the previous in-place fetches in
   * PromptCard / AgentRunnerPromptCard. Note this still caps how far back
   * `getDescriptor` can resolve a label; older versions return `undefined`.
   */
  size?: number;
};

const SORTING = [{ id: "created_at", desc: true }];

/**
 * Loads versions for a prompt and produces stable descriptors (`vN` label +
 * highest-priority stage tag), so the trace prompt card and agent-runner card
 * stay consistent without each rolling its own indexing logic.
 */
export function usePromptVersionsWithLabels(
  promptId: string,
  { enabled = true, staleTime = 60_000, size = 100 }: Options = {},
) {
  const { data, isLoading } = usePromptVersionsById(
    { promptId, page: 1, size, sorting: SORTING },
    { enabled: enabled && Boolean(promptId), staleTime },
  );

  const versions = useMemo(() => data?.content ?? [], [data?.content]);
  const total = data?.total ?? versions.length;

  const descriptors = useMemo<PromptVersionDescriptor[]>(
    () =>
      versions.map((version, index) => ({
        version,
        index,
        label: `v${total - index}`,
        stage: pickHighestStage(version.tags),
      })),
    [versions, total],
  );

  const getDescriptor = useMemo(() => {
    const byId = new Map<string, PromptVersionDescriptor>();
    descriptors.forEach((d) => byId.set(d.version.id, d));
    return (
      versionId: string | undefined,
    ): PromptVersionDescriptor | undefined =>
      versionId ? byId.get(versionId) : undefined;
  }, [descriptors]);

  return {
    versions,
    descriptors,
    total,
    isLoading,
    getDescriptor,
  };
}

export default usePromptVersionsWithLabels;
