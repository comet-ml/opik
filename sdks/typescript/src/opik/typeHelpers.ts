import { BasePrompt } from "@/prompt/BasePrompt";
import { Prompt } from "@/prompt/Prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { PromptVersion } from "@/prompt/PromptVersion";
import type * as OpikApi from "@/rest_api/api";
import { z } from "zod";
import type { Blueprint } from "@/agent-config/Blueprint";

export type SupportedValue =
  | string
  | number
  | boolean
  | BasePrompt
  | PromptVersion
  | unknown[]
  | Record<string, unknown>;

export interface FieldMeta {
  prefixedKey: string;
  backendType: string;
  description?: string;
  isOptional: boolean;
  isJsonEncoded: boolean;
}

export function getSchemaPrefix(schema: z.ZodObject<z.ZodRawShape>): string {
  const prefix = schema._def.description;
  if (!prefix) {
    throw new TypeError(
      "Schema must have a .describe() name — e.g. z.object({...}).describe('MyConfig')"
    );
  }
  return prefix;
}

function unwrapZodType(zodField: z.ZodTypeAny): {
  inner: z.ZodTypeAny;
  isOptional: boolean;
} {
  let inner = zodField;
  let isOptional = false;

  if (inner._def.typeName === 'ZodOptional' || inner._def.typeName === 'ZodNullable') {
    inner = (inner as z.ZodOptional<z.ZodTypeAny>).unwrap();
    isOptional = true;
  }

  return { inner, isOptional };
}

export function zodTypeToBackendType(zodField: z.ZodTypeAny): string {
  const { inner } = unwrapZodType(zodField);

  if (inner._def.typeName === 'ZodString') return "string";
  if (inner._def.typeName === 'ZodBoolean') return "boolean";
  if (inner._def.typeName === 'ZodNumber') {
    const checks: Array<{ kind: string }> =
      (inner._def as { checks?: Array<{ kind: string }> }).checks ?? [];
    return checks.some((c) => c.kind === "int") ? "integer" : "float";
  }
  if (inner._def.typeName === 'ZodArray') return "string";
  if (inner._def.typeName === 'ZodRecord') return "string";
  if (inner._def.typeName === 'ZodObject') return "string";
  if (inner._def.typeName === 'ZodEffects') {
    // Only z.instanceof(Prompt/PromptVersion) is supported. Other ZodEffects (transforms,
    // refines) are intentionally unsupported — map the underlying field to a primitive type
    // before using it in an AgentConfig schema.
    const sentinel = Object.create(null);
    Object.setPrototypeOf(sentinel, Prompt.prototype);
    if (inner.safeParse(sentinel).success) return "prompt";
    Object.setPrototypeOf(sentinel, ChatPrompt.prototype);
    if (inner.safeParse(sentinel).success) return "prompt";
    Object.setPrototypeOf(sentinel, PromptVersion.prototype);
    if (inner.safeParse(sentinel).success) return "prompt_commit";
  }

  // Only a fixed set of Zod primitives are supported in AgentConfig schemas.
  // Unsupported types (z.union, z.enum, z.transform, etc.) must be mapped to a
  // supported primitive before use.
  throw new TypeError(`Unsupported Zod type: ${inner._def.typeName}`);
}

export function extractFieldMetadata(
  schema: z.ZodObject<z.ZodRawShape>,
  prefix: string
): Map<string, FieldMeta> {
  const result = new Map<string, FieldMeta>();

  for (const [fieldName, zodField] of Object.entries(schema.shape)) {
    const field = zodField as z.ZodTypeAny;
    const { inner, isOptional } = unwrapZodType(field);
    const backendType = zodTypeToBackendType(field);
    const description = field._def.description as string | undefined;
    const isJsonEncoded =
      inner._def.typeName === 'ZodArray' ||
      inner._def.typeName === 'ZodRecord' ||
      inner._def.typeName === 'ZodObject';

    result.set(fieldName, {
      prefixedKey: `${prefix}.${fieldName}`,
      backendType,
      description,
      isOptional: isOptional || field.isOptional(),
      isJsonEncoded,
    });
  }

  return result;
}

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

export function serializeFields(
  schema: z.ZodObject<z.ZodRawShape>,
  values: Record<string, unknown>,
  prefix: string
): OpikApi.AgentConfigValueWrite[] {
  const fieldMeta = extractFieldMetadata(schema, prefix);
  const result: OpikApi.AgentConfigValueWrite[] = [];

  for (const [fieldName, meta] of fieldMeta.entries()) {
    const value = values[fieldName];
    if (value === null || value === undefined) {
      result.push({
        key: meta.prefixedKey,
        value: undefined,
        type: "string" as OpikApi.AgentConfigValueWriteType,
        description: meta.description,
      });
      continue;
    }

    result.push({
      key: meta.prefixedKey,
      value: serializeValue(value as SupportedValue, meta.backendType),
      type: meta.backendType as OpikApi.AgentConfigValueWriteType,
      description: meta.description,
    });
  }

  return result;
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

export function deserializeToShape<S extends z.ZodObject<z.ZodRawShape>>(
  schema: S,
  blueprintValues: Record<string, { value?: string | null; type: string }>,
  prefix: string,
  fallback: z.infer<S>,
  resolvedValues?: Record<string, unknown>
): z.infer<S> {
  const fieldMeta = extractFieldMetadata(schema, prefix);
  const result: Record<string, unknown> = {};

  for (const [fieldName, meta] of fieldMeta.entries()) {
    const entry = blueprintValues[meta.prefixedKey];
    if (entry !== undefined) {
      if (
        (meta.backendType === "prompt" || meta.backendType === "prompt_commit") &&
        resolvedValues
      ) {
        result[fieldName] = resolvedValues[meta.prefixedKey];
      } else {
        const raw = deserializeValue(entry.value, entry.type ?? meta.backendType);
        result[fieldName] =
          meta.isJsonEncoded && typeof raw === "string" ? JSON.parse(raw) : raw;
      }
    } else {
      result[fieldName] = (fallback as Record<string, unknown>)[fieldName];
    }
  }

  return result as z.infer<S>;
}

export function matchesBlueprint(
  schema: z.ZodObject<z.ZodRawShape>,
  values: Record<string, unknown>,
  blueprint: Blueprint,
  prefix: string
): boolean {
  const fieldMeta = extractFieldMetadata(schema, prefix);

  for (const [fieldName, meta] of fieldMeta.entries()) {
    const localValue = values[fieldName];
    const blueprintRaw = blueprint.getRawValue(meta.prefixedKey);

    if (localValue === null || localValue === undefined) {
      // null/undefined local → expect the blueprint entry to also be absent/null
      if (blueprintRaw !== undefined) return false;
      continue;
    }

    const serialized = serializeValue(localValue as SupportedValue, meta.backendType);
    if (blueprintRaw === undefined) return false;
    if (blueprintRaw !== serialized) return false;

    const blueprintDesc = blueprint.getFieldDescription(meta.prefixedKey);
    if (meta.description !== blueprintDesc) return false;
  }

  return true;
}

function inferBackendType(
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
