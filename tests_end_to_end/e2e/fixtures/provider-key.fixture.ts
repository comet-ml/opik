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
  deleteProviderKeyById,
  deleteProviderKeyByName,
  findProviderKeyByProvider,
} from '../core/provider-keys';

export interface OauthProviderSeed {
  providerName: string;
  /** Model the mock gateway will echo; unique per test so parallel specs never collide. */
  modelName?: string;
}

export interface ProviderKeysFixture {
  /**
   * REST-seeds a Custom provider in OAuth2 token-auth mode against the suite's mock
   * token service; registered for teardown deletion.
   */
  createOauth(seed: OauthProviderSeed): Promise<void>;
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
