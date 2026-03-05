import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";

import api, { AGENT_CONFIGS_REST_ENDPOINT } from "@/api/api";
import {
  BlueprintDetails,
  EnrichedBlueprintValue,
} from "@/types/agent-configs";
import { PromptCommitInfo } from "@/types/prompts";
import usePromptsByCommits from "@/api/prompts/usePromptsByCommits";

type UseAgentConfigByIdParams = {
  blueprintId: string;
};

const getAgentConfigById = async (
  blueprintId: string,
  signal: AbortSignal,
): Promise<BlueprintDetails> => {
  const { data } = await api.get(
    `${AGENT_CONFIGS_REST_ENDPOINT}blueprints/${blueprintId}`,
    { signal },
  );
  return data;
};

export default function useAgentConfigById({
  blueprintId,
}: UseAgentConfigByIdParams) {
  const { data: blueprint, isPending } = useQuery({
    queryKey: [AGENT_CONFIGS_REST_ENDPOINT, "blueprints", blueprintId],
    queryFn: ({ signal }) => getAgentConfigById(blueprintId, signal),
    enabled: !!blueprintId,
  });

  const promptCommits = useMemo(() => {
    if (!blueprint) return [];
    return blueprint.values
      .filter((v) => v.type === "prompt")
      .map((v) => v.value);
  }, [blueprint]);

  const { data: promptsInfo } = usePromptsByCommits({ commits: promptCommits });

  const promptsMap = useMemo(() => {
    if (!promptsInfo) return {};
    return promptsInfo.reduce<Record<string, PromptCommitInfo>>((acc, info) => {
      acc[info.commit] = info;
      return acc;
    }, {});
  }, [promptsInfo]);

  const enrichedBlueprint = useMemo(() => {
    if (!blueprint) return undefined;
    return {
      ...blueprint,
      values: blueprint.values.map<EnrichedBlueprintValue>((v) => {
        if (v.type !== "prompt") return v;
        const promptInfo = promptsMap[v.value];
        return {
          ...v,
          promptName: promptInfo?.prompt_name,
          promptId: promptInfo?.prompt_id,
          promptVersionId: promptInfo?.prompt_version_id,
        };
      }),
    };
  }, [blueprint, promptsMap]);

  return {
    data: enrichedBlueprint,
    isPending,
  };
}
