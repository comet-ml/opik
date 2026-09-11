import { AsyncLocalStorage } from "node:async_hooks";

type PromptMasks = Record<string, string>;

const promptMaskStorage = new AsyncLocalStorage<PromptMasks | null>();

export function promptMaskContext<R>(
  masks: PromptMasks | null | undefined,
  fn: () => R
): R {
  return promptMaskStorage.run(masks ?? null, fn);
}

export function getActiveMaskForPrompt(promptId: string): string | null {
  const masks = promptMaskStorage.getStore();
  if (masks == null) return null;
  return masks[promptId] ?? null;
}
