import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '../core/mock-auth';
import { createProviderKey, deleteProviderKeyByName } from '../core/provider-keys';
import { permanentFailureBaseUrl } from '../core/failing-provider';

export interface OauthProviderSeed {
  providerName: string;
  /** Model the mock gateway will echo; unique per test so parallel specs never collide. */
  modelName?: string;
}

export interface FailingProviderSeed {
  providerName: string;
  /** Model name; unique per test so parallel specs never collide. */
  modelName?: string;
}

export interface ProviderKeysFixture {
  /**
   * REST-seeds a Custom provider in OAuth2 token-auth mode against the suite's mock
   * token service; registered for teardown deletion.
   */
  createOauth(seed: OauthProviderSeed): Promise<void>;
  /**
   * REST-seeds a Custom provider whose base URL answers a permanent 4xx (see
   * `core/failing-provider.ts`); registered for teardown deletion.
   *
   * Returns the fully-qualified model string — `custom-llm/<provider>/<model>` —
   * that a rule's `code.model.name` must carry byte-for-byte, because the
   * backend resolves the provider from that string verbatim. Returning it
   * rather than leaving the caller to rebuild it removes the one way this seed
   * can silently produce a rule that finds no provider at all.
   *
   * Gate the spec on `permanentFailureSkipReason()` before calling: on a
   * topology where the backend cannot reach the destination this seeds a
   * provider that fails for an entirely different reason.
   */
  createPermanentFailure(seed: FailingProviderSeed): Promise<string>;
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
      async createPermanentFailure({ providerName, modelName = 'permanent-4xx-model' }) {
        registered.push(providerName);
        const model = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: permanentFailureBaseUrl(),
          configuration: { models: model },
        });
        return model;
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
