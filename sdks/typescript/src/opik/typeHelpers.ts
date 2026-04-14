import { BasePrompt } from "@/prompt/BasePrompt";
import { PromptVersion } from "@/prompt/PromptVersion";
import type * as OpikApi from "@/rest_api/api";

export type SupportedValue =
  | string
  | number
  | boolean
  | BasePrompt
  | PromptVersion
  | unknown[]
  | Record<string, unknown>;

export function serializeValue(value: SupportedValue, backendType?: string): string {
  const type = backendType ?? inferBackendType(value);

  if (type === "boolean") return (value as boolean) ? "true" : "false";
  if (type === "integer" || type === "float") {
    if (!Number.isFinite(value as number)) {
      throw new TypeError(`Cannot serialize non-finite number: ${value}`);
    }
    return String(value);
  }
  if (type === "string") {
    if (Array.isArray(value) || (typeof value === "object" && value !== null)) {
      return JSON.stringify(value);
    }
    return value as string;
  }
  if (type === "prompt") {
    const prompt = value as BasePrompt;
    if (!prompt.commit) {
      throw new TypeError("Cannot serialize prompt without a commit");
    }
    return prompt.commit;
  }
  if (type === "prompt_commit") {
    return (value as PromptVersion).commit;
  }
  throw new TypeError(`Unsupported backend type: ${type}`);
}

export function deserializeValue(
  value: string | null | undefined,
  backendType: OpikApi.AgentConfigValuePublicType | string
): string | number | boolean | null {
  if (value === null || value === undefined) return null;
  switch (backendType) {
    case "boolean":
      return value.toLowerCase() === "true";
    case "integer":
      return Math.trunc(Number(value));
    case "float":
      return Number(value);
    case "string":
    case "prompt":
    case "prompt_commit":
      return value;
    default:
      return value;
  }
}

export function inferBackendType(
  value: SupportedValue
): OpikApi.AgentConfigValueWriteType {
  if (typeof value === "boolean") return "boolean";
  if (typeof value === "number") {
    return Number.isInteger(value) ? "integer" : "float";
  }
  if (typeof value === "string") return "string";
  if (value instanceof BasePrompt) return "prompt";
  if (value instanceof PromptVersion) return "prompt_commit";
  if (value === null) return "string";
  if (Array.isArray(value)) return "string";
  if (typeof value === "object") return "string";
  throw new TypeError(`Unsupported value type: ${typeof value}`);
}

// Serialize a plain values record → wire format, types inferred from values
export function serializeValuesRecord(
  values: Record<string, unknown>
): OpikApi.AgentConfigValueWrite[] {
  const result: OpikApi.AgentConfigValueWrite[] = [];
  for (const [key, value] of Object.entries(values)) {
    if (value === null || value === undefined) {
      result.push({
        key,
        value: undefined,
        type: "string" as OpikApi.AgentConfigValueWriteType,
      });
      continue;
    }
    const backendType = inferBackendType(value as SupportedValue);
    result.push({
      key,
      value: serializeValue(value as SupportedValue, backendType),
      type: backendType as OpikApi.AgentConfigValueWriteType,
    });
  }
  return result;
}

// Deserialize blueprint raw values → plain Record using backend-declared types.
// resolvedValues: already-resolved Prompt objects (from Blueprint.values).
// fieldNames: if provided, only extract those keys; else extract all.
export function deserializeFromBlueprint(
  rawValues: Record<string, { value?: string | null; type: string }>,
  resolvedValues: Record<string, unknown>,
  fieldNames?: string[]
): Record<string, unknown> {
  const keys = fieldNames ?? Object.keys(rawValues);
  const result: Record<string, unknown> = {};
  for (const key of keys) {
    const entry = rawValues[key];
    if (!entry) continue;
    if (entry.type === "prompt" || entry.type === "prompt_commit") {
      result[key] = resolvedValues[key];
    } else {
      result[key] = deserializeValue(entry.value, entry.type);
    }
  }
  return result;
}

