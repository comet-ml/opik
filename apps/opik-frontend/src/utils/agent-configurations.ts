import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";
import { formatNumericData } from "@/lib/utils";

export const AGENT_CONFIGURATION_METADATA_KEY = "agent_configuration";
export const AGENT_CONFIGURATION_PROD_ENV_NAME = "prod";

export const isProdTag = (tag: string) => /^prod(uction)?$/i.test(tag);

export const sortTags = (tags: string[]) => [
  ...tags.filter(isProdTag),
  ...tags.filter((t) => !isProdTag(t)),
];

export const formatBlueprintValue = (v: BlueprintValue): string => {
  switch (v.type) {
    case BlueprintValueType.INT:
    case BlueprintValueType.FLOAT: {
      const num = Number(v.value);
      return isNaN(num) ? v.value : formatNumericData(num);
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
