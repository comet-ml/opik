import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";

export const AGENT_CONFIGURATION_METADATA_KEY = "agent_configuration";
export const AGENT_CONFIGURATION_PROD_ENV_NAME = "prod";

export enum AgentConfigurationBasicStage {
  DEV = "dev",
  STAGING = "staging",
  PROD = "prod",
}

export const BASIC_STAGE_ORDER = [
  AgentConfigurationBasicStage.PROD,
  AgentConfigurationBasicStage.STAGING,
  AgentConfigurationBasicStage.DEV,
];

export const isProdTag = (tag: string) =>
  tag == AgentConfigurationBasicStage.PROD;

export const isBasicStage = (tag: string) =>
  BASIC_STAGE_ORDER.some((s) => s === tag);

export const isStageTag = (tag: string, stage: string) => tag === stage;

export const sortTags = (tags: string[]) => [
  ...BASIC_STAGE_ORDER.filter((stage) => tags.some((t) => t === stage)),
  ...tags.filter((t) => !isBasicStage(t)),
];

export const formatBlueprintValue = (v: BlueprintValue): string => {
  switch (v.type) {
    case BlueprintValueType.INT:
    case BlueprintValueType.FLOAT: {
      return v.value;
    }
    case BlueprintValueType.BOOLEAN:
      return v.value === "true" ? "true" : "false";
    default:
      return v.value;
  }
};

export const generateBlueprintDescription = (
  values: Array<{ key: string; value: unknown }>,
): string => {
  if (!values.length) return "";
  const changes = values.map(({ key, value }) => `${key} to ${value}`);
  return `Changed ${changes.join(", ")}`;
};
