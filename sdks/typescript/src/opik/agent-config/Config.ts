import { getTrackContext } from "@/decorators/track";
import type { SupportedValue } from "@/typeHelpers";
import { inferBackendType, serializeValue } from "@/typeHelpers";

function toMetadataValue(value: unknown, backendType: string): unknown {
  if (value === null || value === undefined) return undefined;
  if (backendType === "prompt" || backendType === "prompt_commit") {
    return serializeValue(value as SupportedValue, backendType);
  }
  return value;
}

interface ConfigMeta {
  readonly blueprintId: string | undefined;
  readonly blueprintVersion: string | undefined;
  readonly isFallback: boolean;
}

export type Config<T> = Readonly<T> & ConfigMeta;

const META_KEYS = new Set<string>([
  "blueprintId",
  "blueprintVersion",
  "isFallback",
]);

export interface ConfigOptions<T extends Record<string, unknown>> {
  values: T;
  fieldNames: Set<string>;
  blueprintId: string | undefined;
  blueprintVersion: string | undefined;
  isFallback: boolean;
  maskId: string | undefined;
}

export function createTypedConfig<T extends Record<string, unknown>>(
  options: ConfigOptions<T>
): Config<T> {
  const {
    values,
    fieldNames,
    blueprintId,
    blueprintVersion,
    isFallback,
    maskId,
  } = options;

  const base = { ...(values as Record<string, unknown>) };

  Object.defineProperties(base, {
    blueprintId: { value: blueprintId, enumerable: false, writable: false },
    blueprintVersion: { value: blueprintVersion, enumerable: false, writable: false },
    isFallback: { value: isFallback, enumerable: false, writable: false },
  });

  const proxy = new Proxy(base, {
    get(target, prop: string | symbol) {
      if (typeof prop !== "string") return Reflect.get(target, prop);

      if (META_KEYS.has(prop)) {
        return Reflect.get(target, prop);
      }

      if (fieldNames.has(prop)) {
        injectTraceMetadata({
          blueprintId,
          blueprintVersion,
          maskId,
          fieldNames,
          values: values as Record<string, unknown>,
        });
      }

      return Reflect.get(target, prop);
    },
  });

  return proxy as Config<T>;
}

function injectTraceMetadata(opts: {
  blueprintId: string | undefined;
  blueprintVersion: string | undefined;
  maskId: string | undefined;
  fieldNames: Set<string>;
  values: Record<string, unknown>;
}): void {
  const ctx = getTrackContext();
  if (!ctx) return;

  const { blueprintId, blueprintVersion, maskId, fieldNames, values } = opts;

  const valuesMetadata: Record<string, { value: unknown; type: string }> = {};

  for (const fieldName of fieldNames) {
    const value = values[fieldName];
    if (value === undefined) continue;
    const backendType = inferBackendType(value as SupportedValue);
    valuesMetadata[fieldName] = {
      value: toMetadataValue(value, backendType),
      type: backendType,
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
