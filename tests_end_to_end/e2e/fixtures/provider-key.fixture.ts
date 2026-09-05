import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockAuthClearChatStatus,
  mockAuthForceChatStatus,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '../core/mock-auth';
import { createProviderKey, deleteProviderKeyByName } from '../core/provider-keys';

export interface OauthProviderSeed {
  providerName: string;
  /**
   * Models the mock gateway will echo; unique per test so parallel specs never collide.
   *
   * A list rather than a single name because the mock's counters and its forced-status
   * hook are both keyed on the model in the request body, so several models on ONE
   * provider is how a spec gets several gateway behaviours over one trace without
   * changing anything else about the rule.
   */
  modelNames?: string[];
}

export interface ProviderKeysFixture {
  /**
   * REST-seeds a Custom provider in OAuth2 token-auth mode against the suite's mock
   * token service; registered for teardown deletion.
   */
  createOauth(seed: OauthProviderSeed): Promise<void>;
  /**
   * Makes the mock gateway answer `status` for every chat request naming `modelName`,
   * and clears it at teardown.
   *
   * The hook lives on the mock process, which outlives the test, so it is registered
   * here rather than reset in the test body — a trailing reset step is skipped exactly
   * when an assertion has already failed.
   */
  forceChatStatus(modelName: string, status: number): Promise<void>;
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
    const forcedStatusModels: string[] = [];

    await use({
      async createOauth({ providerName, modelNames = ['mock-model'] }) {
        registered.push(providerName);
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: mockGatewayUrlForBackend,
          configuration: {
            models: modelNames.map((model) => `custom-llm/${providerName}/${model}`).join(','),
          },
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
      async forceChatStatus(modelName, status) {
        forcedStatusModels.push(modelName);
        await mockAuthForceChatStatus(modelName, status);
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
      for (const modelName of forcedStatusModels) {
        try {
          await mockAuthClearChatStatus(modelName);
        } catch (err) {
          console.warn(`[provider-key fixture] status-hook reset warning for ${modelName}:`, err);
        }
      }
    }
  },
});

export { expect } from './automation-rules.fixture';
