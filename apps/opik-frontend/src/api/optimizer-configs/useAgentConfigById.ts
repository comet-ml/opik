import { useMemo } from "react";
import {
  BlueprintDetails,
  EnrichedBlueprintValue,
} from "@/types/optimizer-configs";
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
    createdAt: "2026-02-28T10:27:05.901Z",
    values: [
      {
        key: "model",
        type: "string",
        value: "gpt-4.1-mini",
        description: "The LLM model used for inference",
      },
      {
        key: "temperature",
        type: "number",
        value: "0.1",
        description:
          "Sampling temperature; lower values produce more deterministic outputs",
      },
      {
        key: "max_tokens",
        type: "number",
        value: "1024",
        description: "Maximum number of tokens to generate in the response",
      },
      {
        key: "top_p",
        type: "number",
        value: "0.95",
        description: "Nucleus sampling probability mass",
      },
      {
        key: "stream",
        type: "boolean",
        value: "true",
        description: "Whether to stream tokens as they are generated",
      },
      {
        key: "json_mode",
        type: "boolean",
        value: "false",
        description: "Force the model to respond with valid JSON",
      },
      {
        key: "system_prompt",
        type: "Prompt",
        value: "system-prompt:v3",
        description: "System-level instructions that shape model behaviour",
      },
      {
        key: "user_prompt",
        type: "Prompt",
        value: "user-template:v12",
        description: "Template used to format the user turn",
      },
    ],
  },
  "0195f1d4-2af3-8c72-0be0-2d82c5d0e1b8": {
    id: "0195f1d4-2af3-8c72-0be0-2d82c5d0e1b8",
    description: "Switch to gpt-4.1-mini model",
    createdBy: "user_456",
    createdAt: "2026-02-25T15:42:30.000Z",
    values: [
      {
        key: "model",
        type: "string",
        value: "gpt-4",
        description: "The LLM model used for inference",
      },
      {
        key: "temperature",
        type: "number",
        value: "0.7",
        description:
          "Sampling temperature; lower values produce more deterministic outputs",
      },
      {
        key: "max_tokens",
        type: "number",
        value: "1024",
        description: "Maximum number of tokens to generate in the response",
      },
      {
        key: "top_p",
        type: "number",
        value: "0.95",
        description: "Nucleus sampling probability mass",
      },
      {
        key: "stream",
        type: "boolean",
        value: "true",
        description: "Whether to stream tokens as they are generated",
      },
      {
        key: "json_mode",
        type: "boolean",
        value: "false",
        description: "Force the model to respond with valid JSON",
      },
      {
        key: "system_prompt",
        type: "Prompt",
        value: "system-prompt:v3",
        description: "System-level instructions that shape model behaviour",
      },
      {
        key: "user_prompt",
        type: "Prompt",
        value: "user-template:v12",
        description: "Template used to format the user turn",
      },
    ],
  },
  "0195f1d4-3bg4-9d83-1cf1-3e93d6e1f2c9": {
    id: "0195f1d4-3bg4-9d83-1cf1-3e93d6e1f2c9",
    description: "Increase max tokens to 2048",
    createdBy: "user_123",
    createdAt: "2026-02-23T09:15:00.000Z",
    values: [
      {
        key: "model",
        type: "string",
        value: "gpt-4",
        description: "The LLM model used for inference",
      },
      {
        key: "temperature",
        type: "number",
        value: "0.7",
        description:
          "Sampling temperature; lower values produce more deterministic outputs",
      },
      {
        key: "max_tokens",
        type: "number",
        value: "2048",
        description: "Maximum number of tokens to generate in the response",
      },
      {
        key: "top_p",
        type: "number",
        value: "0.95",
        description: "Nucleus sampling probability mass",
      },
      {
        key: "stream",
        type: "boolean",
        value: "true",
        description: "Whether to stream tokens as they are generated",
      },
      {
        key: "json_mode",
        type: "boolean",
        value: "false",
        description: "Force the model to respond with valid JSON",
      },
      {
        key: "system_prompt",
        type: "Prompt",
        value: "system-prompt:v3",
        description: "System-level instructions that shape model behaviour",
      },
      {
        key: "user_prompt",
        type: "Prompt",
        value: "user-template:v12",
        description: "Template used to format the user turn",
      },
    ],
  },
  "0195f1d4-4ch5-0e94-2dg2-4f04e7f2g3d0": {
    id: "0195f1d4-4ch5-0e94-2dg2-4f04e7f2g3d0",
    description: "Add system prompt v3",
    createdBy: "user_789",
    createdAt: "2026-02-16T14:00:00.000Z",
    values: [
      {
        key: "model",
        type: "string",
        value: "gpt-3.5-turbo",
        description: "The LLM model used for inference",
      },
      {
        key: "temperature",
        type: "number",
        value: "0.7",
        description:
          "Sampling temperature; lower values produce more deterministic outputs",
      },
      {
        key: "max_tokens",
        type: "number",
        value: "1024",
        description: "Maximum number of tokens to generate in the response",
      },
      {
        key: "top_p",
        type: "number",
        value: "1.0",
        description: "Nucleus sampling probability mass",
      },
      {
        key: "stream",
        type: "boolean",
        value: "false",
        description: "Whether to stream tokens as they are generated",
      },
      {
        key: "json_mode",
        type: "boolean",
        value: "false",
        description: "Force the model to respond with valid JSON",
      },
      {
        key: "system_prompt",
        type: "Prompt",
        value: "system-prompt:v3",
        description: "System-level instructions that shape model behaviour",
      },
      {
        key: "user_prompt",
        type: "Prompt",
        value: "user-template:v11",
        description: "Template used to format the user turn",
      },
    ],
  },
  "0195f1d4-5di6-1f05-3eh3-5g15f8g3h4e1": {
    id: "0195f1d4-5di6-1f05-3eh3-5g15f8g3h4e1",
    description: "Initial blueprint configuration",
    createdBy: "user_123",
    createdAt: "2026-02-15T08:30:00.000Z",
    values: [
      {
        key: "model",
        type: "string",
        value: "gpt-3.5-turbo",
        description: "The LLM model used for inference",
      },
      {
        key: "temperature",
        type: "number",
        value: "1.0",
        description:
          "Sampling temperature; lower values produce more deterministic outputs",
      },
      {
        key: "max_tokens",
        type: "number",
        value: "512",
        description: "Maximum number of tokens to generate in the response",
      },
      {
        key: "top_p",
        type: "number",
        value: "1.0",
        description: "Nucleus sampling probability mass",
      },
      {
        key: "stream",
        type: "boolean",
        value: "false",
        description: "Whether to stream tokens as they are generated",
      },
      {
        key: "json_mode",
        type: "boolean",
        value: "false",
        description: "Force the model to respond with valid JSON",
      },
      {
        key: "user_prompt",
        type: "Prompt",
        value: "user-template:v11",
        description: "Template used to format the user turn",
      },
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

export default function useAgentConfigById({
  blueprintId,
}: UseAgentConfigByIdParams) {
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
