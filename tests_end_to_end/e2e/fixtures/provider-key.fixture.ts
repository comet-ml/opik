import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '../core/mock-auth';
import { createProviderKey, deleteProviderKeyByName } from '../core/provider-keys';

export interface OauthProviderSeed {
  providerName: string;
  /** Model the mock gateway will echo; unique per test so parallel specs never collide. */
  modelName?: string;
}

export interface UnreachableProviderSeed {
  providerName: string;
  /** Model name; unique per test so parallel specs never collide. */
  modelName?: string;
}

/**
 * Base URL for a provider that can never answer.
 *
 * The discard port on the backend's own loopback: nothing listens there, so the
 * connect is REFUSED immediately and every scoring call through this provider
 * fails in milliseconds, with no dependency on the mock gateway, on an external
 * host, or on the runner being reachable from the backend at all. That last
 * point is why this is not `mockGatewayUrlForBackend` with a forced status: the
 * mock binds to the test runner, which a remote deployment cannot reach (see
 * `mockAuthSkipReason`), so a mock-based failure spec can only ever run locally.
 *
 * A blackholed address (`192.0.2.1`) would also fail, but by TIMING OUT — that
 * turns a one-second failure into a connect-timeout wait and, worse, can leave
 * the provider call still in flight when the scoring message is reclaimed.
 * Refusal is the deterministic shape.
 */
const UNREACHABLE_BASE_URL = 'http://127.0.0.1:9/v1';

export interface ProviderKeysFixture {
  /**
   * REST-seeds a Custom provider in OAuth2 token-auth mode against the suite's mock
   * token service; registered for teardown deletion.
   */
  createOauth(seed: OauthProviderSeed): Promise<void>;
  /**
   * REST-seeds a Custom provider whose base URL refuses every connection, so any
   * rule pointed at it fails deterministically; registered for teardown deletion.
   *
   * Returns the fully-qualified model id to put on a rule, rather than leaving
   * the caller to rebuild `custom-llm/<provider>/<model>`: a rule naming a model
   * string the provider does not declare fails for the wrong reason, and the
   * two spellings drifting apart would be invisible in the log stream.
   */
  createUnreachable(seed: UnreachableProviderSeed): Promise<string>;
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
      async createUnreachable({ providerName, modelName = 'unreachable-model' }) {
        registered.push(providerName);
        const model = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: UNREACHABLE_BASE_URL,
          // Required whenever `auth_config` is absent. Never sent anywhere: the
          // connection is refused before a request is written.
          api_key: 'unused-the-connection-is-refused',
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
