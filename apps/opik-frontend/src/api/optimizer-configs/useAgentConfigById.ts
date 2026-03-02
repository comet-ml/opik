import { useMemo } from "react";
import { BlueprintDetails, EnrichedBlueprintValue } from "@/types/optimizer-configs";
import { PromptCommitInfo } from "@/types/prompts";
import usePromptsByCommits from "@/api/prompts/usePromptsByCommits";

type UseAgentConfigByIdParams = {
  blueprintId: string;
};

const MOCK_BLUEPRINTS: Record<string, BlueprintDetails> = {
  "0195f1d4-1bf2-7b61-9ad9-1c71b4c9d0a7": {
    id: "0195f1d4-1bf2-7b61-9ad9-1c71b4c9d0a7",
    description: "Reduce temperature for more determinism",
    createdBy: "user_123",
    createdAt: "2026-02-19T10:27:05.901Z",
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
  },
  "0195f1d4-2af3-8c72-0be0-2d82c5d0e1b8": {
    id: "0195f1d4-2af3-8c72-0be0-2d82c5d0e1b8",
    description: "Switch to gpt-4.1-mini model",
    createdBy: "user_456",
    createdAt: "2026-02-18T15:42:30.000Z",
    values: [
      { key: "model", type: "string", value: "gpt-4" },
      { key: "temperature", type: "number", value: "0.7" },
      { key: "max_tokens", type: "number", value: "1024" },
      { key: "top_p", type: "number", value: "0.95" },
      { key: "stream", type: "boolean", value: "true" },
      { key: "json_mode", type: "boolean", value: "false" },
      { key: "system_prompt", type: "Prompt", value: "system-prompt:v3" },
      { key: "user_prompt", type: "Prompt", value: "user-template:v12" },
    ],
  },
  "0195f1d4-3bg4-9d83-1cf1-3e93d6e1f2c9": {
    id: "0195f1d4-3bg4-9d83-1cf1-3e93d6e1f2c9",
    description: "Increase max tokens to 2048",
    createdBy: "user_123",
    createdAt: "2026-02-17T09:15:00.000Z",
    values: [
      { key: "model", type: "string", value: "gpt-4" },
      { key: "temperature", type: "number", value: "0.7" },
      { key: "max_tokens", type: "number", value: "2048" },
      { key: "top_p", type: "number", value: "0.95" },
      { key: "stream", type: "boolean", value: "true" },
      { key: "json_mode", type: "boolean", value: "false" },
      { key: "system_prompt", type: "Prompt", value: "system-prompt:v3" },
      { key: "user_prompt", type: "Prompt", value: "user-template:v12" },
    ],
  },
  "0195f1d4-4ch5-0e94-2dg2-4f04e7f2g3d0": {
    id: "0195f1d4-4ch5-0e94-2dg2-4f04e7f2g3d0",
    description: "Add system prompt v3",
    createdBy: "user_789",
    createdAt: "2026-02-16T14:00:00.000Z",
    values: [
      { key: "model", type: "string", value: "gpt-3.5-turbo" },
      { key: "temperature", type: "number", value: "0.7" },
      { key: "max_tokens", type: "number", value: "1024" },
      { key: "top_p", type: "number", value: "1.0" },
      { key: "stream", type: "boolean", value: "false" },
      { key: "json_mode", type: "boolean", value: "false" },
      { key: "system_prompt", type: "Prompt", value: "system-prompt:v3" },
      { key: "user_prompt", type: "Prompt", value: "user-template:v11" },
    ],
  },
  "0195f1d4-5di6-1f05-3eh3-5g15f8g3h4e1": {
    id: "0195f1d4-5di6-1f05-3eh3-5g15f8g3h4e1",
    description: "Initial blueprint configuration",
    createdBy: "user_123",
    createdAt: "2026-02-15T08:30:00.000Z",
    values: [
      { key: "model", type: "string", value: "gpt-3.5-turbo" },
      { key: "temperature", type: "number", value: "1.0" },
      { key: "max_tokens", type: "number", value: "512" },
      { key: "top_p", type: "number", value: "1.0" },
      { key: "stream", type: "boolean", value: "false" },
      { key: "json_mode", type: "boolean", value: "false" },
      { key: "user_prompt", type: "Prompt", value: "user-template:v11" },
    ],
  },
};

// TODO: Replace mock with real API call
// const getAgentConfigById = async (
//   { signal }: QueryFunctionContext,
//   { blueprintId }: UseAgentConfigByIdParams,
// ) => {
//   const { data } = await api.get(
//     `${OPTIMIZER_CONFIGS_REST_ENDPOINT}blueprint/${blueprintId}`,
//     { signal },
//   );
//   return data;
// };

export default function useAgentConfigById({ blueprintId }: UseAgentConfigByIdParams) {
  const blueprint = MOCK_BLUEPRINTS[blueprintId];

  const promptCommits = useMemo(() => {
    if (!blueprint) return [];
    return blueprint.values
      .filter((v) => v.type === "Prompt")
      .map((v) => v.value);
  }, [blueprint]);

  const { data: promptsInfo } = usePromptsByCommits({ commits: promptCommits });

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
  };
}
