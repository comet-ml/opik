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
  if (Array.isArray(value)) return "string";
  if (typeof value === "object" && value !== null) return "string";
  throw new TypeError(`Unsupported value type: ${typeof value}`);
}

export function serializeValue(value: SupportedValue): string {
  if (typeof value === "boolean") return value ? "true" : "false";
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      throw new TypeError(`Cannot serialize non-finite number: ${value}`);
    }
    return String(value);
  }
  if (typeof value === "string") return value;
  if (value instanceof BasePrompt) {
    if (!value.commit) {
      throw new TypeError("Cannot serialize prompt without a commit");
    }
    return value.commit;
  }
  if (value instanceof PromptVersion) return value.commit;
  if (Array.isArray(value) || (typeof value === "object" && value !== null)) {
    return JSON.stringify(value);
  }
  throw new TypeError(`Unsupported value type: ${typeof value}`);
}

export function deserializeValue(
  value: string,
  backendType: OpikApi.AgentConfigValuePublicType
): string | number | boolean {
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
