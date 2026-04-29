import { useCallback, useEffect, useMemo } from "react";
import first from "lodash/first";
import {
  COMPOSED_PROVIDER_TYPE,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  ProviderModelsMap,
} from "@/types/providers";
import useOpenAICompatibleModels from "@/hooks/useOpenAICompatibleModels";
import useLlmModels, { LlmModelsByProvider } from "@/api/llm/useLlmModels";
import { parseComposedProviderType } from "@/lib/provider";
import { PROVIDERS } from "@/constants/providers";
import {
  getLatestProviderModelsSnapshot,
  KNOWN_PROVIDER_TYPES,
  ModelFlags,
  setLatestModelFlags,
  setLatestProviderModelsSnapshot,
} from "@/lib/modelRegistryStore";

export type ProviderResolver = (
  modelName?: PROVIDER_MODEL_TYPE | "",
) => COMPOSED_PROVIDER_TYPE | "";

export type ModelResolver = (
  lastPickedModel: PROVIDER_MODEL_TYPE | "",
  setupProviders: COMPOSED_PROVIDER_TYPE[],
  preferredProvider?: COMPOSED_PROVIDER_TYPE | "",
) => PROVIDER_MODEL_TYPE | "";

// Re-exported for backwards compatibility with callers that still import
// PROVIDER_MODELS from this module. Actual location is
// src/constants/providerModels.ts; moved there to break the hook ↔ store
// circular import (modelRegistryStore seeds its snapshot from it).
export { PROVIDER_MODELS } from "@/constants/providerModels";

// Re-exported for backwards compat. Actual store is in
// src/lib/modelRegistryStore.ts so the hook file stays focused and the
// circular import with lib/provider.ts is broken.
export {
  getLatestProviderModelsSnapshot,
  getLatestModelFlags,
} from "@/lib/modelRegistryStore";

const MINIMAL_FALLBACK: ProviderModelsMap = {
  [PROVIDER_TYPE.OPIK_FREE]: [
    {
      value: PROVIDER_MODEL_TYPE.OPIK_FREE_MODEL,
      label: "Free model",
    },
  ],
  [PROVIDER_TYPE.OPEN_AI]: [],
  [PROVIDER_TYPE.ANTHROPIC]: [],
  [PROVIDER_TYPE.OPEN_ROUTER]: [],
  [PROVIDER_TYPE.GEMINI]: [],
  [PROVIDER_TYPE.VERTEX_AI]: [],
  [PROVIDER_TYPE.CUSTOM]: [],
  [PROVIDER_TYPE.OLLAMA]: [],
  [PROVIDER_TYPE.BEDROCK]: [],
};

// Convention enforced by scripts/sync_provider_models.py:
// dropdown-curated models carry a `label`, everything else doesn't.
const isDropdownVisible = (m: LlmModelsByProvider[string][number]): boolean =>
  Boolean(m.label);

const transformFetched = (
  fetched: LlmModelsByProvider,
  { onlyVisible }: { onlyVisible: boolean },
): ProviderModelsMap => {
  const out: ProviderModelsMap = {};
  for (const [provider, models] of Object.entries(fetched)) {
    const filtered = onlyVisible ? models.filter(isDropdownVisible) : models;
    out[provider] = filtered.map((m) => ({
      value: (m.qualifiedName ?? m.id) as PROVIDER_MODEL_TYPE,
      label: m.label ?? m.id,
    }));
  }
  return out;
};

const buildFlagsIndex = (
  fetched: LlmModelsByProvider,
): Map<string, ModelFlags> => {
  const index = new Map<string, ModelFlags>();
  for (const models of Object.values(fetched)) {
    for (const m of models) {
      const flags: ModelFlags = {
        reasoning: m.reasoning,
        structuredOutput: m.structuredOutput,
      };
      index.set(m.id, flags);
      if (m.qualifiedName) {
        index.set(m.qualifiedName, flags);
      }
    }
  }
  return index;
};

const useLLMProviderModelsData = () => {
  const { data: fetched, isPending, isError, error } = useLlmModels();
  const openAICompatibleModels = useOpenAICompatibleModels();

  // Dropdown-facing map: only entries that are flagged as visible (i.e.
  // carry a label) appear here. Stable reference across renders.
  const providerModels = useMemo<ProviderModelsMap>(() => {
    const fromApi = fetched
      ? transformFetched(fetched, { onlyVisible: true })
      : {};
    return {
      ...MINIMAL_FALLBACK,
      ...fromApi,
      ...openAICompatibleModels,
    } as ProviderModelsMap;
  }, [fetched, openAICompatibleModels]);

  // Full registry map (labelled + non-labelled entries) used only for
  // provider resolution, not for dropdowns. Needed because persisted
  // prompts can carry a non-curated model id (dated snapshot, removed
  // model, etc.) and `calculateModelProvider` must still resolve it.
  const fullProviderModels = useMemo<ProviderModelsMap>(() => {
    const fromApi = fetched
      ? transformFetched(fetched, { onlyVisible: false })
      : {};
    return {
      ...MINIMAL_FALLBACK,
      ...fromApi,
      ...openAICompatibleModels,
    } as ProviderModelsMap;
  }, [fetched, openAICompatibleModels]);

  // Compat wrapper for callers that still expect a factory. Returns the
  // memoized map, so invoking this produces a stable reference too.
  const getProviderModels = useCallback(
    (): ProviderModelsMap => providerModels,
    [providerModels],
  );

  // Keep the module-level store in sync with the latest data so pure
  // utilities (getProviderFromModel, isReasoningModel) read current
  // values. Two subtleties:
  //
  // 1. The store receives the FULL registry (including non-dropdown
  //    entries like dated snapshots) because `getProviderFromModel` must
  //    resolve every routable model, not just the curated ones.
  //
  // 2. Before `fetched` resolves, we merge onto the existing seeded
  //    snapshot from the store instead of replacing it — otherwise the
  //    first render would clobber the PROVIDER_MODELS-seeded catalog
  //    with an empty map (MINIMAL_FALLBACK + openAICompatibleModels) and
  //    `getProviderFromModel` would fall through to OPEN_AI for every
  //    persisted non-OpenAI model during the hydration window. Once
  //    `fetched` is defined we overwrite the seeded entries with the
  //    CDN-fresh registry, and per-workspace openAICompatibleModels is
  //    always layered on top.
  useEffect(() => {
    const base = fetched
      ? {
          ...MINIMAL_FALLBACK,
          ...transformFetched(fetched, { onlyVisible: false }),
        }
      : getLatestProviderModelsSnapshot();
    setLatestProviderModelsSnapshot({
      ...base,
      ...openAICompatibleModels,
    });
    if (fetched) {
      setLatestModelFlags(buildFlagsIndex(fetched));
    }
  }, [fetched, openAICompatibleModels]);

  const calculateModelProvider: ProviderResolver = useCallback(
    (modelName) => {
      if (!modelName) {
        return "";
      }

      // Resolve against the full registry (not just the dropdown-visible
      // subset) so persisted selections pointing at non-curated models —
      // dated snapshots, historically-selectable entries that were later
      // dropped from the dropdown — still return the correct provider.
      //
      // Two-pass match order:
      //  1. Exact value match across ALL providers first. This is the
      //     unambiguous case; a stored `vertex_ai/gemini-2.5-pro` matches
      //     only Vertex AI, never the Gemini direct entry.
      //  2. Bare-id suffix match as a fallback, for persisted bare ids
      //     like `gemini-2.5-pro` that originally selected a qualified-name
      //     entry. When a bare id exists in multiple providers, preference
      //     goes to the Gemini-direct entry (ordered ahead of Vertex AI in
      //     the snapshot) since that matches the older selection path.
      //
      // Provider-key guard: only accept keys whose parsed base type is in
      // KNOWN_PROVIDER_TYPES. Composed keys like `custom-llm:acme` are
      // fine because parseComposedProviderType extracts the prefix.
      const isKnownProvider = (providerName: string): boolean => {
        const baseType = parseComposedProviderType(
          providerName as COMPOSED_PROVIDER_TYPE,
        );
        return KNOWN_PROVIDER_TYPES.has(baseType);
      };

      const entries = Object.entries(fullProviderModels);

      // Pass 1: exact value match.
      const exact = entries.find(
        ([providerName, models]) =>
          isKnownProvider(providerName) &&
          models.some((pm) => modelName === pm.value),
      );
      if (exact) {
        return exact[0] as COMPOSED_PROVIDER_TYPE;
      }

      // Pass 2: bare-id suffix match against qualified-name values.
      const suffix = entries.find(
        ([providerName, models]) =>
          isKnownProvider(providerName) &&
          models.some(
            (pm) =>
              typeof pm.value === "string" &&
              pm.value.includes("/") &&
              modelName === pm.value.split("/").pop(),
          ),
      );
      return (suffix?.[0] as COMPOSED_PROVIDER_TYPE) ?? "";
    },
    [fullProviderModels],
  );

  const calculateDefaultModel: ModelResolver = useCallback(
    (lastPickedModel, setupProviders, preferredProvider?) => {
      const lastPickedModelProvider = calculateModelProvider(lastPickedModel);

      const isLastPickedModelValid =
        !!lastPickedModelProvider &&
        setupProviders.includes(lastPickedModelProvider);

      if (isLastPickedModelValid) {
        return lastPickedModel;
      }

      const composedProviderType =
        preferredProvider ?? (first(setupProviders) || "");
      const providerType = parseComposedProviderType(composedProviderType);

      if (composedProviderType && providerType) {
        if (PROVIDERS[providerType]?.defaultModel) {
          return PROVIDERS[providerType].defaultModel;
        } else {
          return first(providerModels[composedProviderType])?.value ?? "";
        }
      }

      return "";
    },
    [calculateModelProvider, providerModels],
  );

  return {
    providerModels,
    getProviderModels,
    calculateModelProvider,
    calculateDefaultModel,
    isPending,
    isError,
    error,
  };
};

export default useLLMProviderModelsData;
