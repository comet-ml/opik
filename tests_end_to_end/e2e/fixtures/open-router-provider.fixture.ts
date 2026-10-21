import { test as baseTest, expect } from './openai-responses-stub.fixture';
import { ensureBuiltInProviderKey } from '../core/provider-keys';
import { registerUnbilledModel } from '../core/llm-model-policy';

/**
 * The OpenRouter models these specs drive, named by the behaviour each one
 * stands for rather than by vendor name — the same convention as
 * `REGISTRY_MODEL`, and for the same reason: a registry reshuffle is one edit
 * here.
 *
 * On OpenRouter the picker's display name IS the model id, so these double as
 * both.
 *
 * Every one is the cheapest member of its class that still shows the behaviour,
 * and nothing here ever reaches OpenRouter (see the fixture header), but a spec
 * pinned to a flagship by name is one stubbing mistake away from being
 * expensive.
 */
export const OPEN_ROUTER_MODEL = {
  /**
   * A reasoning model whose OpenRouter registry row PUBLISHES its levels:
   * `reasoning_efforts: [high, medium, low, minimal]`, and
   * `supported_parameters` without `temperature` or `top_p`. So its panel must
   * offer the published five and mount no sampling control at all.
   */
  publishedEfforts: 'openai/gpt-5-mini',
  /**
   * A reasoning model whose row lists `reasoning` but publishes NO
   * `reasoning_efforts`, so the panel has to fall back to
   * `getNativeModelBehindOpenRouter` + the native OpenAI row to find levels.
   *
   * `o3-mini` matches `llm-model-policy`'s forbidden fragment `o3`, which is
   * why this fixture registers it unbilled — see the header.
   */
  nativeFallbackEfforts: 'openai/o3-mini',
} as const;

/** The group label the model picker renders OpenRouter's built-in models under. */
export const OPEN_ROUTER_GROUP = 'OpenRouter';

/**
 * The placeholder written when the workspace has no OpenRouter key and the
 * runner holds none either. Same spelling as the `modelRegistryProviders`
 * fixture's, so a human finding either in a workspace reads one thing.
 */
const PLACEHOLDER_KEY = 'opik-e2e-placeholder-not-a-working-key';

export interface OpenRouterProviderRef {
  /** True once the workspace has an OpenRouter key, however it got there. */
  configured: boolean;
  /** Whether this run had to add the key, as opposed to finding one. */
  added: boolean;
  /** True when the key this run added is the non-working placeholder. */
  placeholder: boolean;
}

export interface OpenRouterProviderFixtures {
  openRouterProvider: OpenRouterProviderRef;
}

/**
 * Makes the model pickers offer OpenRouter's registry models, by ensuring the
 * workspace has an OpenRouter key.
 *
 * ## Why this is not just `modelRegistryProviders` with one more provider
 *
 * It could have been, and that was tried first. `REGISTRY_PROVIDERS` is read by
 * every spec that takes `modelRegistryProviders` — it is what their
 * `expect(providers).toContain('openai')` precondition reads — so adding
 * OpenRouter there would have written a fifth placeholder key on every one of
 * those runs, for a provider none of them selects from. The cost of a
 * placeholder is real (see that fixture's header on `@provider-sanity`), so it
 * belongs to the specs that need it.
 *
 * ## Why a real key and not a browser-side stub
 *
 * The same finding as `modelRegistryProviders`: answering
 * `GET /v1/private/llm-provider-key/` with `page.route` leaves the playground's
 * `useModelSelection` memo on an empty provider config in a fraction of
 * parallel runs, and the parameters panel then renders with controls missing —
 * which is indistinguishable from the regression these specs exist to catch.
 *
 * ## Why nothing is torn down
 *
 * A built-in provider is ONE row per workspace, so a fixture that deleted its
 * own key would pull OpenRouter out from under any spec running in parallel
 * that needs it, and there is no reference count to arbitrate. Strictly
 * additive — the same choice `ensureBuiltInProviderKey` and
 * `ConfigurationPage.ensureProviderConfigured` both make.
 *
 * ## Why registering the models unbilled is honest here
 *
 * `openai/o3-mini` matches `llm-model-policy`'s forbidden `o3` fragment, so a
 * POM would refuse to select it without this. The exemption is sound because
 * every spec using this fixture short-circuits `POST /v1/private/chat/completions`
 * in the browser (`captureCompletionBody`), so no request reaches OpenRouter
 * even when the workspace holds a real key: the assertions are on the body Opik
 * BUILT, never on an answer to it.
 */
export const test = baseTest.extend<OpenRouterProviderFixtures>({
  openRouterProvider: async ({}, use) => {
    for (const model of Object.values(OPEN_ROUTER_MODEL)) registerUnbilledModel(model);

    const realKey = process.env.OPENROUTER_API_KEY;
    const { added } = await ensureBuiltInProviderKey(
      'openrouter',
      realKey || PLACEHOLDER_KEY,
    );
    const placeholder = added && !realKey;

    if (placeholder) {
      // Said out loud rather than only returned: this is the one case that
      // leaves state behind, and the run log is where someone debugging a later
      // @provider-sanity auth failure will look.
      console.warn(
        '[openRouterProvider fixture] added a PLACEHOLDER OpenRouter key — the workspace ' +
          'had none and the runner holds no OPENROUTER_API_KEY. It is not removed; see ' +
          'this fixture’s header.',
      );
    }

    await use({ configured: true, added, placeholder });
  },
});

export { expect };
