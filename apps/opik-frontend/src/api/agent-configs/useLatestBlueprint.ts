import { useEffect, useMemo } from "react";
import {
  BlueprintDetails,
  EnrichedBlueprintValue,
} from "@/types/agent-configs";
import { PromptCommitInfo } from "@/types/prompts";
import usePromptsByCommits from "@/api/prompts/usePromptsByCommits";

type UseLatestBlueprintParams = {
  projectId: string;
};

const MOCK_BLUEPRINT: BlueprintDetails = {
  id: "0195f1d4-1bf2-7b61-9ad9-1c71b4c9d0a7",
  description: "Reduce temperature for more determinism",
  created_by: "user_123",
  created_at: "2026-02-28T10:27:05.901Z",
  values: [
    { key: "model", type: "string", value: "gpt-4.1-mini" },
    { key: "temperature", type: "number", value: "0.1" },
    { key: "max_tokens", type: "number", value: "1024" },
    { key: "top_p", type: "number", value: "0.95" },
    { key: "stream", type: "boolean", value: "true" },
    { key: "json_mode", type: "boolean", value: "false" },
    { key: "system_prompt", type: "Prompt", value: "system-prompt:v3" },
    { key: "user_prompt", type: "Prompt", value: "user-template:v12" },
  ],
};

// TODO: Replace mock with real API call
// const getLatestBlueprint = async (
//   { signal }: QueryFunctionContext,
//   { projectId }: UseLatestBlueprintParams,
// ) => {
//   const { data } = await api.get(
//     `${AGENT_CONFIGS_REST_ENDPOINT}blueprint/retrieve`,
//     {
//       signal,
//       params: {
//         project_id: projectId,
//       },
//     },
//   );
//   return data;
// };

export default function useLatestBlueprint(
  _params: UseLatestBlueprintParams,
) {
  const blueprint = MOCK_BLUEPRINT;

  const promptCommits = useMemo(() => {
    return blueprint.values
      .filter((v) => v.type === "Prompt")
      .map((v) => v.value);
  }, [blueprint.values]);

  const { data: promptsInfo, isPending: isPromptsPending } =
    usePromptsByCommits({ commits: promptCommits });

  const promptsMap = useMemo(() => {
    if (!promptsInfo) return {};

    return promptsInfo.reduce<Record<string, PromptCommitInfo>>(
      (acc, info) => {
        acc[info.commit] = info;
        return acc;
      },
      {},
    );
  }, [promptsInfo]);

  const enrichedBlueprint = useMemo(() => {
    if (!blueprint) return undefined;

    return {
      ...blueprint,
      values: blueprint.values.map<EnrichedBlueprintValue>((v) => {
        if (v.type !== "Prompt") return v;

        const promptInfo = promptsMap[v.value];
        return {
          ...v,
          promptName: promptInfo?.prompt_name,
          promptId: promptInfo?.prompt_id,
        };
      }),
    };
  }, [blueprint, promptsMap]);

  return {
    data: enrichedBlueprint,
    isPending: false,
    isPromptsPending,
  };
}
