import { AsyncLocalStorage } from "node:async_hooks";

interface ConfigContext {
  maskId?: string;
  blueprintName?: string;
}

const configContextStorage = new AsyncLocalStorage<ConfigContext>();

export function agentConfigContext<T>(
  blueprintName: string | undefined,
  maskId: string | undefined,
  fn: () => T | Promise<T>,
): T | Promise<T> {
  return configContextStorage.run({ maskId, blueprintName }, fn) as T | Promise<T>;
}

export function getActiveConfigMask(): string | null {
  return configContextStorage.getStore()?.maskId ?? null;
}

export function getActiveConfigBlueprintName(): string | null {
  return configContextStorage.getStore()?.blueprintName ?? null;
}
