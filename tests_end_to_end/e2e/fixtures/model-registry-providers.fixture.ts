import { test as baseTest, expect } from './quick-filter-logs.fixture';
import { ensureBuiltInProviderKey } from '../core/provider-keys';
import { registerUnbilledModel } from '../core/llm-model-policy';

/**
 * The built-in providers whose registry models these specs select from, paired
 * with the env var a real key would come from.
 */
export const REGISTRY_PROVIDERS = [
  { provider: 'openai', envVar: 'OPENAI_API_KEY' },
  { provider: 'anthropic', envVar: 'ANTHROPIC_API_KEY' },
  { provider: 'gemini', envVar: 'GEMINI_API_KEY' },
  { provider: 'vertex-ai', envVar: 'VERTEX_AI_API_KEY' },
] as const;

/**
 * The placeholder written when the workspace has no key for a provider and the
 * runner holds none either.
 *
 * Named so that a human finding it in a workspace knows at once what it is and
 * that it cannot work.
 */
const PLACEHOLDER_KEY = 'opik-e2e-placeholder-not-a-working-key';

/**
 * The provider group labels as the model picker renders them. Needed because
 * two providers legitimately offer the same model under the same display name
 * — "Gemini 3 Flash Preview" is both `gemini-3-flash-preview` and
 * `vertex_ai/gemini-3-flash-preview` — so a spec driving the cross-provider
 * case has to say which group it means.
 */
export const PROVIDER_GROUP = {
  openAi: 'OpenAI',
  anthropic: 'Anthropic',
  gemini: 'Gemini',
  vertexAi: 'Vertex AI',
} as const;

/**
 * The models these specs drive, named by the behaviour each one stands for
 * rather than by vendor name, so a spec reads as "the OpenAI reasoning model"
 * and a future model swap is one edit here.
 *
 * Every one is the CHEAPEST member of its class that still shows the behaviour
 * — `GPT 5 Mini` rather than the flagship `GPT 5.4`, both of which are
 * `reasoning: true` in `OPENAI_MODEL_CAPABILITIES` and so take the identical
 * branch. Nothing here ever reaches a provider, but a spec pinned to a flagship
 * by name is one stubbing mistake away from being expensive, and
 * `llm-model-policy` exists because that mistake has been made before.
 */
export const REGISTRY_MODEL = {
  /** OpenAI reasoning: tunes neither sampling param and takes no penalties. */
  openAiReasoning: 'GPT 5 Mini',
  /** OpenAI non-reasoning: the control that proves the strip is conditional. */
  openAiStandard: 'GPT 4o Mini',
  /** Gemini 3 generation: drops temperature and top_p. Offered by Gemini AND Vertex AI. */
  gemini3: 'Gemini 3 Flash Preview',
  /** Gemini 2.x control: keeps both. Offered by Gemini AND Vertex AI. */
  gemini2: 'Gemini 2.5 Flash',
  /**
   * OpenAI reasoning model declaring `responsesApiOnlyEffortOptions: ["max"]` —
   * the gate opik#8682 added.
   *
   * `GPT 5.6 Sol` and not one of the `gpt-6-*` rows that carry the same flag:
   * the branch is identical for every member of the set, and `gpt-6` is on
   * `llm-model-policy`'s forbidden list precisely so a spec cannot drift onto a
   * flagship. Nothing here reaches a provider, but picking the cheapest member
   * that still shows the behaviour keeps that true by construction.
   */
  openAiResponsesOnlyEffort: 'GPT 5.6 Sol',
  /**
   * The control beside it: a reasoning model with the SAME five Chat
   * Completions effort values and NO `responsesApiOnlyEffortOptions`, so Max
   * must never appear for it under either pipeline mode.
   *
   * Sharing the base option set is what makes it a control — a model with a
   * different base list would differ in more than the one variable under test.
   */
  openAiEffortWithoutMax: 'GPT 5.5',
  /** A Claude model with `supportsSamplingParams: false` — nothing a rule can set. */
  claudeWithoutSampling: 'Claude Sonnet 5',
  /** A Claude model that still offers the sampling choice. */
  claudeWithSampling: 'Claude Sonnet 4.6',
} as const;

export interface ModelRegistryProvidersRef {
  /** The provider types the model pickers will offer, all of them configured. */
  providers: readonly string[];
  /** Providers this run had to add a key for, as opposed to finding one. */
  added: readonly string[];
}

export interface ModelRegistryProvidersFixtures {
  modelRegistryProviders: ModelRegistryProvidersRef;
}

/**
 * Makes the model pickers offer the registry's OpenAI, Anthropic, Gemini and
 * Vertex AI models, by ensuring the workspace has a key for each.
 *
 * ## Why a real key and not a browser-side stub
 *
 * Answering `GET /v1/private/llm-provider-key/` with `page.route` looks like the
 * cleaner option — no workspace mutation at all — and it was tried first. It is
 * NOT a faithful substitute: with the route stubbed, the playground's
 * `useModelSelection` memo lands on an empty provider config in roughly a third
 * of parallel runs and never recovers, so the parameters panel renders with no
 * Max output tokens. With real keys the same loop was clean 12 runs out of 12.
 * A fixture that changes the state machine it is only meant to enable makes
 * every assertion downstream of it a coin toss, which is worse than the
 * mutation it avoids.
 *
 * ## Why nothing is torn down
 *
 * A built-in provider is ONE row per workspace. A fixture that deleted its own
 * key would pull the provider out from under any spec running in parallel that
 * needs the same one, and there is no reference count to arbitrate. So this is
 * strictly additive — the same choice `ConfigurationPage.ensureProviderConfigured`
 * makes, and for the same reason.
 *
 * ## What that leaves behind, and when it matters
 *
 * When the workspace already has a key — which is the normal case on staging and
 * on any CI workspace — this fixture writes NOTHING and the question does not
 * arise. When it has none and the runner holds none either, a clearly-named
 * placeholder is written and stays. The one cost of that is the
 * `@provider-sanity` specs: they skip when a provider is absent, so a
 * placeholder turns a clean skip into an auth failure on a workspace where no
 * real key was ever available. That is a loud failure on a bare workspace, not
 * a silent wrong answer, and `added` is reported so a caller can say so.
 *
 * Measured on the bare local harvest workspace, writing the placeholder changed
 * the playground + online-evaluation directories only for the better: it turned
 * no skip into a failure, and it un-skipped `playground-model-parameters.spec.ts`
 * — which gates on the provider being present but stubs its own completion, so
 * it never needed a working key. `playground-providers.spec.ts` keeps skipping,
 * because `@provider-sanity` gates on the API-key env var rather than on
 * workspace state.
 *
 * None of the specs using this ever reaches a provider: the playground ones
 * short-circuit `chat/completions` in the browser, and the rule dialog is never
 * submitted. The models are registered unbilled on that basis.
 */
export const test = baseTest.extend<ModelRegistryProvidersFixtures>({
  modelRegistryProviders: async ({}, use) => {
    for (const model of Object.values(REGISTRY_MODEL)) registerUnbilledModel(model);

    const added: string[] = [];
    // Tracked per provider rather than inferred from "the runner holds some
    // key": the env vars are independent, so a run with OPENAI_API_KEY set and
    // ANTHROPIC_API_KEY absent still writes a placeholder for Anthropic — and a
    // workspace-wide check would suppress the warning about exactly that.
    const placeholders: string[] = [];
    for (const { provider, envVar } of REGISTRY_PROVIDERS) {
      const realKey = process.env[envVar];
      const result = await ensureBuiltInProviderKey(provider, realKey || PLACEHOLDER_KEY);
      if (!result.added) continue;
      added.push(provider);
      if (!realKey) placeholders.push(provider);
    }

    if (placeholders.length) {
      // Said out loud rather than only returned: this is the one case that
      // leaves state behind, and the run log is where someone debugging a
      // later @provider-sanity auth failure will look.
      console.warn(
        `[modelRegistryProviders fixture] added a PLACEHOLDER key for ${placeholders.join(', ')} — ` +
          'the workspace had none and the runner holds no real key for them. It is not ' +
          'removed; see this fixture’s header.',
      );
    }

    await use({
      providers: REGISTRY_PROVIDERS.map((p) => p.provider),
      added,
    });
  },
});

export { expect };
