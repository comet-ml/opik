import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AiProviderRef {
  id: string;
  providerName: string;
}

/**
 * A custom (vLLM / OpenAI-compatible) provider to seed. Every field is optional
 * because the specs that use this differ in exactly one of them — a static
 * `apiKey` or an `authConfig` recipe, never both, which is the mutual exclusion
 * under test.
 */
export interface CustomProviderSeed {
  /** Distinguishes several providers inside one test; appended to the run-namespaced name. */
  suffix?: string;
  baseUrl?: string;
  models?: string;
  apiKey?: string;
  /** Sent verbatim (snake_case), because the recipe's exact shape is the contract. */
  authConfig?: Record<string, unknown>;
}

export interface AiProviderPool {
  /** Run-namespaced prefix every provider this pool creates is named under. */
  namePrefix: string;
  createCustom(seed?: CustomProviderSeed): Promise<AiProviderRef>;
}

export interface AiProviderFixtures {
  aiProviders: AiProviderPool;
}

const DEFAULT_BASE_URL = 'https://example.invalid/v1';
const DEFAULT_MODELS = 'gpt-4o-mini';

/**
 * AI provider configurations, created through the REST API and swept at teardown.
 *
 * A fixture rather than in-test setup for two reasons. First, provider keys are
 * workspace-scoped: they hang off no project, so nothing cascades them away and
 * the run-prefix sweep in `global-teardown.ts` (experiments, datasets, projects)
 * never sees them. Left behind they are permanent workspace state — and every
 * later run of these specs would then be reading a workspace it did not shape.
 * Second, a trailing cleanup step is skipped the moment an earlier assertion
 * throws, which is precisely when a provider has been created and something has
 * gone wrong.
 *
 * Teardown discovers what to delete by listing the workspace and matching the
 * run-namespaced prefix, rather than deleting a list of ids the test registered.
 * A provider created by a call that then failed its assertion still carries the
 * prefix and is still swept; an id-registration API would leak exactly those.
 *
 * Names are namespaced per test and worker, so parallel workers cannot collide
 * on the (workspace, provider, provider_name) the backend treats as unique.
 */
export const test = baseTest.extend<AiProviderFixtures>({
  aiProviders: async ({ backendClient, testNamespace }, use, testInfo) => {
    const namePrefix = testNamespace;

    const pool: AiProviderPool = {
      namePrefix,
      async createCustom(seed: CustomProviderSeed = {}): Promise<AiProviderRef> {
        const providerName = seed.suffix ? `${namePrefix}-${seed.suffix}` : namePrefix;
        const { status, message, id } = await backendClient.createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: seed.baseUrl ?? DEFAULT_BASE_URL,
          configuration: { models: seed.models ?? DEFAULT_MODELS },
          ...(seed.apiKey === undefined ? {} : { api_key: seed.apiKey }),
          ...(seed.authConfig === undefined ? {} : { auth_config: seed.authConfig }),
        });
        // Loud, not silent: a seed that did not land would otherwise surface as
        // a confusing assertion failure several steps later.
        if (status !== 201 || !id) {
          throw new Error(
            `[aiProviders fixture] could not create "${providerName}" -> ${status}: ${message}`,
          );
        }
        return { id, providerName };
      },
    };

    await use(pool);

    if (shouldLeaveArtifacts(testInfo)) {
      console.warn(`[aiProviders fixture] leaving providers under ${namePrefix} for debugging`);
      return;
    }

    // Best-effort: a cleanup failure warns rather than throws, so it cannot mask
    // the assertion error that explains the run.
    try {
      const configured = await backendClient.listProviderKeys();
      const ids = configured
        .filter((provider) => provider.providerName?.startsWith(namePrefix))
        .map((provider) => provider.id);
      await backendClient.deleteProviderKeys(ids);
    } catch (err) {
      console.warn(`[aiProviders fixture] cleanup warning for ${namePrefix}:`, err);
    }
  },
});

export { expect } from './automation-rules.fixture';
