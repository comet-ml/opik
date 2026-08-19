import { test as baseTest } from './aged-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { LlmProviderKeyRef } from '../core/backend';

export interface CustomProviderSeed {
  /** Suffix appended to the test's namespace to form `provider_name`. */
  suffix: string;
  baseUrl?: string;
  /** Provider settings — `models`, `auth_header_name`, … */
  configuration?: Record<string, string>;
  /** Static-key mode. Mutually exclusive with `authConfig` on the backend. */
  apiKey?: string;
  /** Dynamic-token mode: the token-fetch recipe, in the API's snake_case shape. */
  authConfig?: Record<string, unknown>;
}

export interface CustomProviderRef {
  id: string;
  providerName: string;
  baseUrl: string;
}

export interface AiProviderSeeder {
  /**
   * Namespaced `provider_name`. Everything carrying this prefix is swept on
   * teardown, so a provider added through the *UI* needs no separate tracking.
   */
  name(suffix: string): string;
  /** Seed a custom provider through the API. */
  seed(seed: CustomProviderSeed): Promise<CustomProviderRef>;
  /** Every provider in this test's namespace, whoever created it. */
  list(): Promise<LlmProviderKeyRef[]>;
}

export interface AiProviderFixtures {
  aiProviders: AiProviderSeeder;
}

const DEFAULT_BASE_URL = 'https://vllm.example.invalid/v1';

/**
 * Custom AI provider configurations, namespaced per test and swept afterwards.
 *
 * They are **workspace-scoped**: they do not cascade with a project, and the
 * run-prefix sweep in `global-teardown.ts` only knows about experiments,
 * datasets and projects. Left behind, they accumulate in every workspace the
 * suite runs against and — because `provider_name` is unique per workspace — a
 * later run reusing a name would fail to create.
 *
 * Teardown deletes by *namespace prefix* rather than by a list of handed-out
 * ids, so a provider created through the dialog under test is swept too, as is
 * one whose creating step threw half-way.
 */
export const test = baseTest.extend<AiProviderFixtures>({
  aiProviders: async ({ backendClient, testNamespace }, use, testInfo) => {
    const name = (suffix: string) => `${testNamespace}-${suffix}`;

    const seeder: AiProviderSeeder = {
      name,
      async seed(seed: CustomProviderSeed): Promise<CustomProviderRef> {
        const providerName = name(seed.suffix);
        const baseUrl = seed.baseUrl ?? DEFAULT_BASE_URL;
        const id = await backendClient.createLlmProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: baseUrl,
          ...(seed.configuration === undefined ? {} : { configuration: seed.configuration }),
          ...(seed.apiKey === undefined ? {} : { api_key: seed.apiKey }),
          ...(seed.authConfig === undefined ? {} : { auth_config: seed.authConfig }),
        });
        return { id, providerName, baseUrl };
      },
      list: () => backendClient.listLlmProviderKeysWithPrefix(testNamespace),
    };

    await use(seeder);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        const leftovers = await backendClient.listLlmProviderKeysWithPrefix(testNamespace);
        await backendClient.deleteLlmProviderKeys(leftovers.map((provider) => provider.id));
      } catch (err) {
        console.warn(`[aiProviders fixture] sweep warning for ${testNamespace}:`, err);
      }
    }
  },
});

export { expect } from './aged-experiment.fixture';
