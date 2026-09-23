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

export interface UnreachableProviderSeed {
  providerName: string;
  /** Model name; unique per test so parallel specs never collide. */
  modelName?: string;
}

/**
 * Seed for `createUnresponsive`. Structurally identical to
 * {@link UnreachableProviderSeed} and named separately on purpose: the two
 * fixtures produce OPPOSITE failure shapes — refused vs hung — and a caller
 * reading `createUnresponsive(seed: UnreachableProviderSeed)` in the editor or
 * in generated docs is told the wrong one.
 */
export type UnresponsiveProviderSeed = UnreachableProviderSeed;

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

/**
 * Base URL for a provider that never answers and never refuses.
 *
 * `192.0.2.1` is TEST-NET-1 (RFC 5737) — reserved for documentation, routed
 * nowhere — so a connect to it hangs until it times out instead of being
 * refused. That is the opposite of what `UNREACHABLE_BASE_URL` wants, and
 * deliberately so: a refused connect ends a Playground run in milliseconds,
 * which leaves no window in which a spec can click Stop. Holding the run open
 * is the whole point here.
 *
 * Only for specs that abort the run themselves and assert on the FRONTEND's
 * reaction. Do NOT point a scoring rule at this — the warning on
 * `UNREACHABLE_BASE_URL` stands, and an online-scoring message whose provider
 * call is still connecting when the message is reclaimed is exactly the
 * non-determinism that URL exists to avoid. Stop aborts the browser's own
 * request; whether the backend's upstream connect is still timing out behind it
 * is not something a caller here may depend on either way.
 */
const UNRESPONSIVE_BASE_URL = 'http://192.0.2.1/v1';

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
   * REST-seeds a Custom provider whose base URL is blackholed, so a call to it
   * hangs rather than failing — which is what keeps a Playground run open long
   * enough for a spec to stop it. Registered for teardown deletion.
   *
   * Returns the fully-qualified model id, for the same reason
   * `createUnreachable` does.
   */
  createUnresponsive(seed: UnresponsiveProviderSeed): Promise<string>;
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
      async createUnresponsive({ providerName, modelName = 'unresponsive-model' }) {
        registered.push(providerName);
        const model = `custom-llm/${providerName}/${modelName}`;
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: UNRESPONSIVE_BASE_URL,
          // Required whenever `auth_config` is absent. Never sent anywhere: the
          // connect never completes, so no request is ever written.
          api_key: 'unused-the-connection-never-completes',
          configuration: { models: model },
        });
        return model;
      },
      async forceChatStatus(modelName, status) {
        forcedStatusModels.push(modelName);
        await mockAuthForceChatStatus(modelName, status);
      },
      register(providerName) {
        registered.push(providerName);
      },
    });

    // Cleared unconditionally, unlike the provider keys below. Retention exists so a
    // failed run leaves INSPECTABLE state in the workspace; a forced status is not that.
    // It is mutable global state on the mock process, which outlives this test and
    // serves every other spec in the run, so leaving one set turns one failure into a
    // second, unrelated one that is very hard to read.
    for (const modelName of forcedStatusModels) {
      try {
        await mockAuthClearChatStatus(modelName);
      } catch (err) {
        console.warn(`[provider-key fixture] status-hook reset warning for ${modelName}:`, err);
      }
    }

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
