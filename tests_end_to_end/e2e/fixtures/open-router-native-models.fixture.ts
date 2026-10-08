import { test as baseTest, expect } from './moved-trace-threads.fixture';
import { ensureBuiltInProviderKey } from '../core/provider-keys';
import { registerUnbilledModel } from '../core/llm-model-policy';
import {
  fetchRegistryModels,
  registryModelIds,
  type RegistryModelsMap,
} from '../core/model-registry';

/**
 * Makes the playground and the rule dialog offer OpenRouter's models, and hands
 * a spec the deployment's own model registry so it can assert the preconditions
 * its cases depend on.
 *
 * ## Why this is separate from `modelRegistryProviders`
 *
 * That fixture ensures a key for the four providers whose NATIVE lists the
 * panel reads; this one ensures the single OpenRouter key and nothing else, so
 * a spec about the OpenRouter routing gate does not silently add placeholder
 * keys for four providers it never selects from. Both are strictly additive and
 * neither tears down, for the reason `ensureBuiltInProviderKey` documents: a
 * built-in provider is one row per workspace, so deleting one would pull it out
 * from under any spec running in parallel.
 *
 * ## What it guarantees, and what it leaves to the spec
 *
 * The gate reads the deployment's RUNTIME registry — the frontend seeds
 * `modelRegistryStore` from the in-tree constant and then overwrites it with
 * `GET /v1/private/llm/models` on mount — so every expectation downstream is
 * conditional on what that answer contains. This fixture owns the half that is
 * the same for every case, and asserts it before a browser is opened: each id
 * it names really is offered under OpenRouter, so a missing model fails here,
 * naming the registry, instead of surfacing as a picker that would not select.
 *
 * It deliberately does NOT assert the native-row memberships. Those differ per
 * case and ARE the thing under test — whether `gpt-5-nano` is in OpenAI's list
 * is precisely why its OpenRouter id is gated, and whether `o3-mini-high` is
 * absent from it is precisely why its id is not. A spec asserts the one its
 * case depends on, next to the case, through `registry`.
 *
 * ## Why naming real models here bills nothing
 *
 * The panel renders off the model identifier, and every spec using this
 * short-circuits `POST /v1/private/chat/completions` in the browser
 * (`captureCompletionBody`) or never submits the dialog at all, so no request
 * reaches OpenRouter. The ids are registered unbilled on exactly that basis —
 * which is also what lets `openai/o3` through `llm-model-policy`'s forbidden
 * fragments, where it would otherwise be rejected as a flagship.
 */

/** The built-in OpenRouter group's label, as the model pickers render it. */
export const OPEN_ROUTER_GROUP = 'OpenRouter';

/**
 * The env var a real OpenRouter key would come from, and the placeholder
 * written when neither the workspace nor the runner has one. Named so a human
 * finding it in a workspace knows at once what it is and that it cannot work.
 */
const OPEN_ROUTER_KEY_ENV = 'OPENROUTER_API_KEY';
const PLACEHOLDER_KEY = 'opik-e2e-placeholder-not-a-working-key';

/**
 * The OpenRouter ids these specs drive, named by the behaviour each stands for.
 *
 * On OpenRouter the picker's display name IS the id, routing suffix included,
 * so these double as the labels the POMs search for.
 */
export const OPEN_ROUTER_MODEL = {
  /** OpenAI reasoning, native row present: loses sampling AND penalties. */
  openAiReasoning: 'openai/gpt-5-nano',
  /** The same id with a routing suffix: still gated, still sent suffixed. */
  openAiReasoningBatch: 'openai/gpt-5-nano:batch',
  /** OpenAI non-reasoning, native row present: keeps all four. */
  openAiStandard: 'openai/gpt-4o-mini',
  /** The same, suffixed: the suffix must not turn the gate on. */
  openAiStandardBatch: 'openai/gpt-4o-mini:batch',
  /** An `openai/` id with NO native row: nothing to read, so it keeps all four. */
  openAiOnlyOnOpenRouter: 'openai/gpt-5-chat',
  /** Gemini 3, native row present: loses sampling, KEEPS both penalties. */
  gemini3: 'google/gemini-3-flash-preview',
  /** OpenAI reasoning with a native row — gated despite the shared id stem. */
  nativeRowReasoning: 'openai/o3',
  /** Same vendor, same registry flag, NO native row — not gated. */
  noNativeRowReasoning: 'openai/o3-mini-high',
  /** A vendor the pattern does not match at all — never gated. */
  unmatchedVendorReasoning: 'deepseek/deepseek-r1',
} as const;

/** The four wire keys an OpenRouter-routed OpenAI reasoning model takes none of. */
export const SAMPLING_AND_PENALTY_KEYS = {
  temperature: 'temperature',
  topP: 'top_p',
  frequencyPenalty: 'frequency_penalty',
  presencePenalty: 'presence_penalty',
} as const;

/** The control ids behind those four, as the panel mounts them. */
export const SAMPLING_CONTROLS = ['temperature', 'topP'] as const;
export const PENALTY_CONTROLS = ['frequencyPenalty', 'presencePenalty'] as const;

export interface OpenRouterNativeModelsRef {
  /** True when this run had to add the OpenRouter key rather than find one. */
  added: boolean;
  /** True when the key it added is the non-working placeholder. */
  placeholder: boolean;
  /** The whole registry answer, for a spec that wants to assert more of it. */
  registry: RegistryModelsMap;
}

export interface OpenRouterNativeModelsFixtures {
  openRouterNativeModels: OpenRouterNativeModelsRef;
}

export const test = baseTest.extend<OpenRouterNativeModelsFixtures>({
  openRouterNativeModels: async ({}, use) => {
    for (const model of Object.values(OPEN_ROUTER_MODEL)) registerUnbilledModel(model);

    const realKey = process.env[OPEN_ROUTER_KEY_ENV];
    const { added } = await ensureBuiltInProviderKey(
      'openrouter',
      realKey || PLACEHOLDER_KEY,
    );
    const placeholder = added && !realKey;
    if (placeholder) {
      // Said out loud as well as returned: this is the one case that leaves
      // state behind, and the run log is where someone debugging a later
      // @provider-sanity auth failure will look.
      console.warn(
        `[openRouterNativeModels fixture] added a PLACEHOLDER OpenRouter key — the workspace ` +
          `had none and ${OPEN_ROUTER_KEY_ENV} is unset. It is not removed; see this fixture’s header.`,
      );
    }

    const registry = await fetchRegistryModels();

    // The OpenRouter group has to offer every id a spec will search for,
    // otherwise the picker assertion downstream would be the first thing to
    // fail and would read as a UI defect.
    const openRouterIds = registryModelIds(registry, 'openrouter');
    for (const model of Object.values(OPEN_ROUTER_MODEL)) {
      expect(
        openRouterIds.has(model),
        `this deployment's registry offers "${model}" under OpenRouter`,
      ).toBe(true);
    }

    await use({ added, placeholder, registry });
  },
});

export { expect };
