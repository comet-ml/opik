import { AsyncLocalStorage } from "node:async_hooks";

interface ConfigContext {
  maskId?: string;
  blueprintName?: string;
}

const configContextStorage = new AsyncLocalStorage<ConfigContext>();

// maskId accepts undefined for backward compatibility — callers may pass
// only blueprintName (to pin a config version without a mask overlay).
export function agentConfigContext<T>(
  maskId: string | undefined,
  fn: () => T | Promise<T>,
  blueprintName?: string,
): T | Promise<T> {
  return configContextStorage.run({ maskId, blueprintName }, fn) as T | Promise<T>;
}

export function getActiveConfigMask(): string | null {
  return configContextStorage.getStore()?.maskId ?? null;
}

export function getActiveConfigBlueprintName(): string | null {
  return configContextStorage.getStore()?.blueprintName ?? null;
}
