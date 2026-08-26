import { test as baseTest } from './provider-key.fixture';
import type { BuiltInProvider } from '../core/backend';

export interface BuiltInProviderKeysFixture {
  /**
   * Make sure the workspace has a provider key for a built-in provider, so its
   * models appear in the model picker. Idempotent, and safe to call for the
   * same provider from several tests at once.
   */
  ensure(provider: BuiltInProvider): Promise<void>;
}

export interface BuiltInProviderKeyFixtures {
  builtInProviderKeys: BuiltInProviderKeysFixture;
}

/**
 * Seeds provider keys for Opik's built-in providers (Gemini, Vertex AI) so a
 * spec can drive UI that is gated on a provider being configured — the model
 * picker, and the per-provider Model parameters form behind it.
 *
 * The key is a throwaway string, never a real credential. That is enough for
 * every assertion these specs make: `POST /v1/private/llm-provider-key` does
 * not call the provider to validate the key, and rendering the picker and the
 * config form only needs the provider to be *configured*. A spec that actually
 * runs a completion needs a real key and belongs in the `@provider-sanity`
 * suite instead (see `pom/model-availability.ts`).
 *
 * **Teardown is deliberately not here.** Built-in provider keys are
 * WORKSPACE-GLOBAL and unique per provider, so concurrent tests share one row:
 * deleting it after the first of them finished would strip the model picker out
 * from under the others still running, which is a flake this fixture would be
 * manufacturing rather than a leak it was preventing. The keys are stamped with
 * the run prefix in `name` and swept once, after every worker is done, by
 * `global-teardown.ts` — the same place the run's projects, datasets and
 * experiments are swept, and for the same reason.
 */
export const test = baseTest.extend<BuiltInProviderKeyFixtures>({
  builtInProviderKeys: async ({ backendClient, envConfig }, use) => {
    await use({
      async ensure(provider) {
        const existing = await backendClient.findBuiltInProviderKey(provider);
        // Someone else's key — an operator's real credential, or one another
        // worker seeded moments ago. Either way it satisfies the requirement
        // and is not ours to replace.
        if (existing) return;
        await backendClient.storeBuiltInProviderKey(provider, {
          apiKey: `${envConfig.cujPrefix}-not-a-real-key`,
          // The sweep's only handle: built-in providers take no
          // `provider_name`, so `name` is where the run stamps itself.
          name: `${envConfig.cujPrefix}-${provider}`,
        });
      },
    });
  },
});

export { expect } from './provider-key.fixture';
