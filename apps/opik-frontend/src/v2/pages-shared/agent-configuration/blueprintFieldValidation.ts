import { z } from "zod";

import { BlueprintValueType } from "@/types/agent-configs";

export const BLUEPRINT_FIELD_NAME_PATTERN = /^[a-zA-Z_][a-zA-Z0-9_]*$/;

const nonEmptyString = z.string().min(1, "Must not be empty");

const FIELD_SCHEMAS: Partial<Record<BlueprintValueType, z.ZodType>> = {
  [BlueprintValueType.INT]: nonEmptyString.pipe(
    z.coerce.number().int("Must be an integer"),
  ),
  [BlueprintValueType.FLOAT]: nonEmptyString.pipe(
    z.coerce.number({ message: "Must be a valid number" }),
  ),
  [BlueprintValueType.STRING]: nonEmptyString,
};

export const validateBlueprintFieldValue = (
  type: BlueprintValueType,
  value: string,
): string => {
  const schema = FIELD_SCHEMAS[type];
  if (!schema) return "";
  const result = schema.safeParse(value.trim());
  return result.success ? "" : result.error.issues[0]?.message ?? "Invalid";
};
