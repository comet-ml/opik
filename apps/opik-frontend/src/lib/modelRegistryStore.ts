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
 *    PROVIDER_MODELS + REASONING_MODELS constants that this branch keeps
 *    in-tree (see useLLMProviderModelsData.ts, OPIK-5022 will delete them).
 *    This eliminates the hydration-window class of bugs where a pre-fetch
 *    read would return the wrong provider/flags for every persisted
 *    non-OpenAI model on every cold load.
 *
 *    The hook overwrites these cells on mount with the merged CDN data,
 *    so fresh-from-CDN models benefit from accurate flags as soon as the
 *    fetch resolves; known-since-release models work correctly from
 *    render 1.
 */

import { PROVIDER_MODELS } from "@/constants/providerModels";
import { REASONING_MODELS } from "@/constants/llm";
import {
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";

export type ModelFlags = {
  reasoning: boolean;
  structuredOutput: boolean;
};

const buildInitialFlags = (): Map<string, ModelFlags> => {
  const index = new Map<string, ModelFlags>();
  const reasoningSet = new Set<string>(
    REASONING_MODELS as readonly PROVIDER_MODEL_TYPE[],
  );
  for (const models of Object.values(PROVIDER_MODELS)) {
    for (const m of models) {
      index.set(m.value, {
        reasoning: reasoningSet.has(m.value),
        // structuredOutput isn't tracked by the static constants; default
        // to false. The hook overwrites on mount with the BE-sourced flag,
        // so this only governs the first render for models known at
        // release time.
        structuredOutput: false,
      });
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
