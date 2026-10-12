/**
 * Module-level store bridging the React Query registry cache to pure utility
 * functions that can't call hooks (getProviderFromModel, isReasoningModel).
 *
 * Why this exists as a separate module:
 *
 * 1. Cycle-free. `hooks/useLLMProviderModelsData.ts` and `lib/provider.ts`
 *    both need to participate here; importing this module from both sides
 *    avoids the quiet circular import they would otherwise form.
 *
 * 2. Explicit ownership. The cells below are mutable module state — a
 *    necessary evil for the "pure util reads hook-derived data" bridge.
 *    Keeping them in a dedicated file makes the boundary obvious and
 *    testable, rather than hidden inside the hook implementation.
 *
 * 3. Seeded at init. The cells start populated from the static
 *    PROVIDER_MODELS constant that this branch keeps in-tree (see
 *    useLLMProviderModelsData.ts, OPIK-5022 will delete it). This
 *    eliminates the hydration-window class of bugs where a pre-fetch
 *    read would return the wrong provider for every persisted
 *    non-OpenAI model on every cold load.
 *
 *    The hook overwrites these cells on mount with the merged CDN data,
 *    so fresh-from-CDN models benefit from accurate flags as soon as the
 *    fetch resolves; known-since-release models work correctly from
 *    render 1.
 */

import { PROVIDER_MODELS } from "@/constants/providerModels";
import { PROVIDER_TYPE, ProviderModelsMap } from "@/types/providers";

export type ModelFlags = {
  reasoning: boolean;
  structuredOutput: boolean;
  supportedParameters?: string[];
  reasoningEfforts?: string[];
};

const buildInitialFlags = (): Map<string, ModelFlags> => {
  const index = new Map<string, ModelFlags>();
  for (const models of Object.values(PROVIDER_MODELS)) {
    for (const m of models) {
      // Real flags arrive from the backend on mount; OPENAI_MODEL_CAPABILITIES outranks them for OpenAI.
      index.set(m.value, { reasoning: false, structuredOutput: false });
    }
  }
  return index;
};

const INITIAL_SNAPSHOT: ProviderModelsMap = PROVIDER_MODELS;
const INITIAL_FLAGS: Map<string, ModelFlags> = buildInitialFlags();

let latestSnapshot: ProviderModelsMap = INITIAL_SNAPSHOT;
let latestFlags: Map<string, ModelFlags> = INITIAL_FLAGS;

export const getLatestProviderModelsSnapshot = (): ProviderModelsMap =>
  latestSnapshot;

export const getLatestModelFlags = (
  model?: string | null,
): ModelFlags | undefined => {
  if (!model) return undefined;
  return latestFlags.get(model);
};

export const setLatestProviderModelsSnapshot = (
  snapshot: ProviderModelsMap,
): void => {
  latestSnapshot = snapshot;
};

export const setLatestModelFlags = (flags: Map<string, ModelFlags>): void => {
  latestFlags = flags;
};

/**
 * Reset the store to its initial (static-constant-seeded) state.
 * Intended for test suites that want a clean slate between cases.
 */
export const resetModelRegistryStoreForTesting = (): void => {
  latestSnapshot = INITIAL_SNAPSHOT;
  latestFlags = INITIAL_FLAGS;
};

/**
 * Known FE provider keys. Used by `getProviderFromModel` to guard against
 * YAML-backed snapshot keys we don't recognize (future provider shipped via
 * CDN before matching FE metadata).
 */
export const KNOWN_PROVIDER_TYPES: ReadonlySet<string> = new Set(
  Object.values(PROVIDER_TYPE),
);
