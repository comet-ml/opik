import { BlueprintValue, BlueprintValueType } from "@/types/agent-configs";

const LONG_VALUE_THRESHOLD = 80;

export const isMultiLineField = (value: BlueprintValue): boolean => {
  if (value.type === BlueprintValueType.PROMPT) return true;
  if (value.type === BlueprintValueType.BOOLEAN) return false;
  if (value.type === BlueprintValueType.INT) return false;
  if (value.type === BlueprintValueType.FLOAT) return false;
  const text = value.value ?? "";
  return text.includes("\n") || text.length > LONG_VALUE_THRESHOLD;
};

export const collectMultiLineKeys = (values: BlueprintValue[]): string[] =>
  values.filter((v) => isMultiLineField(v)).map((v) => v.key);

export const collectNonPromptMultiLineKeys = (
  values: BlueprintValue[],
): string[] =>
  values
    .filter((v) => v.type !== BlueprintValueType.PROMPT && isMultiLineField(v))
    .map((v) => v.key);

export const hasAnyExpandableField = (values: BlueprintValue[]): boolean =>
  values.some(
    (v) => v.type === BlueprintValueType.PROMPT || isMultiLineField(v),
  );
