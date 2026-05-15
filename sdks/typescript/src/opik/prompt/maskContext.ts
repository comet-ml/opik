import { AsyncLocalStorage } from "node:async_hooks";

type PromptMasks = Record<string, string>;

const promptMaskStorage = new AsyncLocalStorage<PromptMasks | null>();

export function promptMaskContext<T>(
  masks: PromptMasks | null | undefined,
  fn: () => T | Promise<T>
): T | Promise<T> {
  return promptMaskStorage.run(masks ?? null, fn) as T | Promise<T>;
}

export function getActiveMaskForPrompt(promptId: string): string | null {
  const masks = promptMaskStorage.getStore();
  if (masks == null) return null;
  return masks[promptId] ?? null;
}
