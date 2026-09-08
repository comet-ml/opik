import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '../core/mock-auth';
import {
  createProviderKey,
  deleteProviderKeyByName,
  notFoundProviderBaseUrl,
} from '../core/provider-keys';

export interface OauthProviderSeed {
  providerName: string;
  /** Model the mock gateway will echo; unique per test so parallel specs never collide. */
  modelName?: string;
}

export interface FailingProviderSeed {
  providerName: string;
  /**
   * Model segment of the qualified id. Needs no per-test uniqueness of its own —
   * `providerName` already namespaces the qualified id — so it defaults to a
   * readable constant.
   */
  modelName?: string;
}

export interface ProviderKeysFixture {
  /**
   * REST-seeds a Custom provider in OAuth2 token-auth mode against the suite's mock
   * token service; registered for teardown deletion.
   */
  createOauth(seed: OauthProviderSeed): Promise<void>;
  /**
   * REST-seeds a Custom provider whose endpoint always answers a permanent HTTP
   * 404, and returns the qualified model id (`custom-llm/<provider>/<model>`) to
   * put in a rule or a Playground run.
   *
   * For specs about what Opik does when a provider REFUSES, which is otherwise
   * awkward to stage: a real provider needs credentials, and the suite's mock
   * gateway only exists on the test runner, so a remote backend can never reach
   * it (see `mockAuthSkipReason`). This one has no such gate — see
   * `notFoundProviderBaseUrl` for how.
   */
  createPermanentlyFailing(seed: FailingProviderSeed): Promise<string>;
  /**
   * Registers a provider name for teardown deletion without seeding — for tests where
   * UI creation is itself the behavior under test. Cleanup runs even when the test fails.
   */
  register(providerName: string): void;
}

export interface ProviderKeyFixtures {
  providerKeys: ProviderKeysFixture;
}

/**
 * Provider keys are WORKSPACE-GLOBAL, so every spec must use testNamespace-prefixed
 * names and delete what it creates — this fixture owns the delete half.
 */
export const test = baseTest.extend<ProviderKeyFixtures>({
  // eslint-disable-next-line no-empty-pattern
  providerKeys: async ({}, use, testInfo) => {
    const registered: string[] = [];

    await use({
      async createOauth({ providerName, modelName = 'mock-model' }) {
        registered.push(providerName);
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: mockGatewayUrlForBackend,
          configuration: { models: `custom-llm/${providerName}/${modelName}` },
          auth_config: {
            token_url: mockTokenUrlForBackend,
            send_as: 'basic',
            credentials: [
              { key: 'grant_type', value: 'client_credentials', secret: false },
              { key: 'client_id', value: MOCK_AUTH_CLIENT_ID, secret: false },
              { key: 'client_secret', value: MOCK_AUTH_CLIENT_SECRET, secret: true },
            ],
          },
        });
      },
      async createPermanentlyFailing({ providerName, modelName = 'unreachable-model' }) {
        registered.push(providerName);
        const qualifiedModel = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: notFoundProviderBaseUrl,
          // Never presented to a real provider — the request 404s at routing —
          // but the field is required for a provider that is not in token mode.
          api_key: 'not-a-real-key',
          configuration: { models: qualifiedModel },
        });
        return qualifiedModel;
      },
      register(providerName) {
        registered.push(providerName);
      },
    });

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const name of registered) {
        try {
          await deleteProviderKeyByName(name);
        } catch (err) {
          console.warn(`[provider-key fixture] delete warning for ${name}:`, err);
        }
      }
    }
  },
});

export { expect } from './automation-rules.fixture';
