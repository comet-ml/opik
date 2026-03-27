import { AsyncLocalStorage } from "node:async_hooks";

const configMaskStorage = new AsyncLocalStorage<string | null>();

export function agentConfigContext<T>(
  maskId: string,
  fn: () => T | Promise<T>
): T | Promise<T> {
  return configMaskStorage.run(maskId, fn) as T | Promise<T>;
}

export function getActiveConfigMask(): string | null {
  return configMaskStorage.getStore() ?? null;
}
