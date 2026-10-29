import { ProviderModelsMap } from "@/types/providers";

/**
 * Adds per-provider models on top of the registry ones, for models only some callers accept (e.g. the rule
 * dialog lists decisions models). Returns the registry map itself when there is nothing to add, and never
 * mutates either input.
 */
export const mergeProviderModels = (
  registryProviderModels: ProviderModelsMap,
  extraProviderModels?: ProviderModelsMap,
): ProviderModelsMap => {
  if (!extraProviderModels) {
    return registryProviderModels;
  }
  const merged: ProviderModelsMap = { ...registryProviderModels };
  Object.entries(extraProviderModels).forEach(([provider, models]) => {
    const existing = merged[provider] ?? [];
    merged[provider] = [
      ...existing,
      ...models.filter((m) => !existing.some((e) => e.value === m.value)),
    ];
  });
  return merged;
};
