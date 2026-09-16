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
import {
  createProviderKey,
  deleteProviderKeyById,
  deleteProviderKeyByName,
  findProviderKeyByProvider,
} from '../core/provider-keys';

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

export interface GeminiProviderKeyRef {
  /** The provider slug the API and the model picker agree on. */
  provider: 'gemini';
  /** The picker's group label for this provider — the scope a model lookup needs. */
  groupLabel: 'Gemini';
  /**
   * True when this fixture created the key (and will delete it), false when it
   * adopted one the environment already had (and will leave it alone).
   */
  seeded: boolean;
}

export interface ProviderKeyFixtures {
  providerKeys: ProviderKeysFixture;
  /**
   * Makes the Gemini models selectable in every model picker, without calling
   * an LLM: the key holds a dummy secret, which is enough for the FE — the
   * picker lists a provider's models as soon as a key for it exists.
   *
   * Only for specs that assert on FORM state and on what a save persists. A
   * spec that needs a completion needs a real key and must not use this.
   */
  geminiProviderKey: GeminiProviderKeyRef;
}

/**
 * Not a real credential, and deliberately shaped so that it reads as one in a
 * request log: nothing this fixture supports ever reaches Google.
 */
const DUMMY_GEMINI_API_KEY = 'e2e-dummy-key-no-completions-are-made-with-this';

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

  // eslint-disable-next-line no-empty-pattern
  geminiProviderKey: async ({}, use, testInfo) => {
    // A workspace holds at most one key per built-in provider and the name
    // cannot be namespaced the way `providerKeys.createOauth` namespaces a
    // custom provider, so this is adopt-or-seed rather than seed: if the
    // environment already has a Gemini key (a real one on a CI workspace, say)
    // it is used as-is and left in place. Deleting a key this fixture did not
    // create would break whatever configured it.
    //
    // Because the resource is workspace-global and unnamespaced, specs using
    // this fixture must not run concurrently with each other — see the serial
    // mode on online-evaluation-thinking-level.spec.ts.
    //
    // The other writer of this same key is playground-providers.spec.ts, which
    // self-provisions Gemini through the AI Providers UI and then wants a real
    // completion from it. The two do not collide today because that spec is
    // @provider-sanity, which runs on its own cadence rather than in the tier
    // ladder — but running both at once would let it adopt this dummy key and
    // fail on auth. Keep them in separate runs.
    let createdId: string | null = null;
    if (!(await findProviderKeyByProvider('gemini'))) {
      createdId = await createProviderKey({ provider: 'gemini', api_key: DUMMY_GEMINI_API_KEY });
      if (createdId === null) {
        // Refusing to continue rather than leaking: without the id this
        // fixture cannot delete the key it just created, and a stray Gemini
        // key changes what every later run's model picker offers.
        throw new Error(
          'gemini provider key was created but answered no Location header — ' +
            'it cannot be addressed for teardown',
        );
      }
    }

    await use({ provider: 'gemini', groupLabel: 'Gemini', seeded: createdId !== null });

    if (createdId !== null && !shouldLeaveArtifacts(testInfo)) {
      try {
        await deleteProviderKeyById(createdId);
      } catch (err) {
        console.warn('[provider-key fixture] gemini key delete warning:', err);
      }
    }
  },
});

export { expect } from './automation-rules.fixture';
