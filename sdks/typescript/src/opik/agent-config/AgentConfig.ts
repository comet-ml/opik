import { getTrackContext } from "@/decorators/track";
import { z } from "zod";
import type { FieldMeta, SupportedValue } from "@/typeHelpers";
import { serializeValue } from "@/typeHelpers";

function toMetadataValue(value: unknown, backendType: string): unknown {
  if (value === null || value === undefined) return undefined;
  if (backendType === "prompt" || backendType === "prompt_commit") {
    return serializeValue(value as SupportedValue, backendType);
  }
  return value;
}

interface AgentConfigMeta {
  readonly blueprintId: string | undefined;
  readonly blueprintVersion: string | undefined;
  readonly envs: string[] | undefined;
  readonly isFallback: boolean;
  deployTo(env: string): Promise<void>;
}

export type AgentConfig<T> = Readonly<T> & AgentConfigMeta;

const META_KEYS = new Set<string>([
  "blueprintId",
  "blueprintVersion",
  "envs",
  "isFallback",
  "deployTo",
]);

export interface AgentConfigOptions<S extends z.ZodObject<z.ZodRawShape>> {
  schema: S;
  values: z.infer<S>;
  fieldMeta: Map<string, FieldMeta>;
  blueprintId: string | undefined;
  blueprintVersion: string | undefined;
  envs: string[] | undefined;
  isFallback: boolean;
  maskId: string | undefined;
  deployTo: (env: string) => Promise<void>;
}

export function createTypedAgentConfig<S extends z.ZodObject<z.ZodRawShape>>(
  options: AgentConfigOptions<S>
): AgentConfig<z.infer<S>> {
  const {
    schema,
    values,
    fieldMeta,
    blueprintId,
    blueprintVersion,
    envs,
    isFallback,
    maskId,
    deployTo,
  } = options;

  const schemaFieldNames = new Set(Object.keys(schema.shape));

  const base = { ...(values as Record<string, unknown>) };

  Object.defineProperties(base, {
    blueprintId: { value: blueprintId, enumerable: false, writable: false },
    blueprintVersion: { value: blueprintVersion, enumerable: false, writable: false },
    envs: { value: envs, enumerable: false, writable: false },
    isFallback: { value: isFallback, enumerable: false, writable: false },
    deployTo: { value: deployTo, enumerable: false, writable: false },
  });

  const proxy = new Proxy(base, {
    get(target, prop: string | symbol) {
      if (typeof prop !== "string") return Reflect.get(target, prop);

      if (META_KEYS.has(prop)) {
        return Reflect.get(target, prop);
      }

      if (schemaFieldNames.has(prop)) {
        injectTraceMetadata({
          blueprintId,
          blueprintVersion,
          maskId,
          fieldMeta,
          values: values as Record<string, unknown>,
        });
      }

      return Reflect.get(target, prop);
    },
  });

  return proxy as AgentConfig<z.infer<S>>;
}

function injectTraceMetadata(opts: {
  blueprintId: string | undefined;
  blueprintVersion: string | undefined;
  maskId: string | undefined;
  fieldMeta: Map<string, FieldMeta>;
  values: Record<string, unknown>;
}): void {
  const ctx = getTrackContext();
  if (!ctx) return;

  const { blueprintId, blueprintVersion, maskId, fieldMeta, values } = opts;

  const valuesMetadata: Record<
    string,
    { value: unknown; type: string; description?: string }
  > = {};

  for (const [fieldName, meta] of fieldMeta.entries()) {
    valuesMetadata[meta.prefixedKey] = {
      value: toMetadataValue(values[fieldName], meta.backendType),
      type: meta.backendType,
      description: meta.description,
    };
  }

  const agentConfiguration: Record<string, unknown> = {
    _blueprint_id: blueprintId,
    blueprint_version: blueprintVersion,
    values: valuesMetadata,
  };
  if (maskId !== undefined) {
    agentConfiguration["_mask_id"] = maskId;
  }
  const metadata = { agent_configuration: agentConfiguration };

  ctx.span.update({ metadata });
  ctx.trace.update({ metadata });
}
