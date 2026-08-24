import { test as baseTest } from './automation-rules.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendClient } from '../core/backend';

/** A custom-LLM provider configured for OAuth2 client-credentials token auth. */
export interface TokenAuthProviderRef {
  id: string;
  /** `provider_name` — what the AI providers table shows in the Name column. */
  providerName: string;
  tokenUrl: string;
  clientId: string;
  /**
   * The plaintext the fixture wrote. It is never readable back through the API
   * by design, so the tests assert its ABSENCE from every response; keeping it
   * on the ref is what makes that assertion possible.
   */
  clientSecret: string;
}

/** A custom-LLM provider configured the classic way, with a static API key. */
export interface StaticKeyProviderRef {
  id: string;
  providerName: string;
  /** The plaintext key written at create time; reads back masked, never in the clear. */
  apiKey: string;
}

export interface AiProviderKeyFixtures {
  tokenAuthProvider: TokenAuthProviderRef;
  staticKeyProvider: StaticKeyProviderRef;
}

/**
 * The auth service these recipes point at deliberately does not exist.
 *
 * Nothing in this file's tests performs a token fetch — they assert how the
 * recipe is stored, masked and switched — so a reachable auth service would be
 * dead infrastructure. `.invalid` is reserved by RFC 2606 and can never
 * resolve, which keeps the fixture from depending on egress, and `https` keeps
 * it acceptable to the strict SSRF destination guard that cloud deployments run
 * (that guard only runs at fetch time, but a recipe that could never be saved
 * there would be a worse fixture).
 */
const TOKEN_URL_HOST = 'auth.opik-e2e-does-not-exist.invalid';

/** Backend's `LlmProvider` discriminator for the Custom / OpenAI-compatible provider. */
const CUSTOM_PROVIDER = 'custom-llm';

/**
 * The Edit dialog's form schema requires a base URL and a non-empty models list
 * before it will submit, so a fixture whose provider lacks either would make
 * every UI save fail on validation rather than on the behaviour under test.
 * Models are stored API-side already prefixed (`custom-llm/<name>/<model>`).
 */
const BASE_URL = 'https://gateway.opik-e2e-does-not-exist.invalid/v1';
const MODEL = 'qa-model';

async function deleteQuietly(backendClient: BackendClient, id: string, label: string) {
  try {
    await backendClient.deleteProviderKeys([id]);
  } catch (err) {
    console.warn(`[${label} fixture] delete warning for ${id}:`, err);
  }
}

/**
 * Two AI provider keys for the dynamic-token-auth tests (OPIK-7940), created
 * through the REST API rather than the UI so a failure points at the behaviour
 * under test instead of at the add-provider dialog.
 *
 * Both are torn down explicitly: provider keys are workspace-scoped, cascade
 * with nothing, and the run-prefix sweep in `global-teardown.ts` only knows
 * about experiments, datasets and projects. Names are namespaced per test, so
 * parallel workers cannot collide on the backend's per-workspace uniqueness.
 */
export const test = baseTest.extend<AiProviderKeyFixtures>({
  tokenAuthProvider: async ({ backendClient, testNamespace }, use, testInfo) => {
    const providerName = `${testNamespace}-oauth`.slice(0, 150);
    const clientSecret = `${testNamespace}-secret-value`;
    const tokenUrl = `https://${TOKEN_URL_HOST}/oauth/token`;
    const clientId = `${testNamespace}-client-id`;

    const created = await backendClient.createProviderKey({
      provider: CUSTOM_PROVIDER,
      providerName,
      baseUrl: BASE_URL,
      models: `${CUSTOM_PROVIDER}/${providerName}/${MODEL}`,
      authConfig: {
        token_url: tokenUrl,
        send_as: 'basic',
        credentials: [
          { key: 'grant_type', value: 'client_credentials', secret: false },
          { key: 'client_id', value: clientId, secret: false },
          { key: 'client_secret', value: clientSecret, secret: true },
        ],
      },
    });
    if (created.status !== 201 || !created.id) {
      throw new Error(
        `[tokenAuthProvider fixture] create returned ${created.status}: ${created.message}`,
      );
    }

    // Prove the precondition rather than assuming it. Every assertion downstream
    // is about a STORED secret; if the recipe had not landed with client_secret
    // flagged secret, the UI checks would still pass against a provider that
    // simply has nothing to hide, and the spec would read as coverage forever.
    const stored = await backendClient.getProviderKey(created.id);
    const storedSecret = stored?.value.authConfig?.credentials.find(
      (credential) => credential.key === 'client_secret',
    );
    if (!storedSecret?.secret) {
      throw new Error(
        `[tokenAuthProvider fixture] provider ${created.id} did not store client_secret as a ` +
          `secret credential: ${JSON.stringify(stored?.value.authConfig)}`,
      );
    }

    const ref: TokenAuthProviderRef = {
      id: created.id,
      providerName,
      tokenUrl,
      clientId,
      clientSecret,
    };
    await testInfo.attach('opik.tokenAuthProvider', {
      // The plaintext secret is a fixture-generated string with no value outside
      // this run, but there is no reason to write it into a report artifact.
      body: JSON.stringify({ ...ref, clientSecret: '<redacted>' }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      await deleteQuietly(backendClient, created.id, 'tokenAuthProvider');
    }
  },

  staticKeyProvider: async ({ backendClient, testNamespace }, use, testInfo) => {
    const providerName = `${testNamespace}-static`.slice(0, 150);
    const apiKey = `${testNamespace}-static-key`;

    const created = await backendClient.createProviderKey({
      provider: CUSTOM_PROVIDER,
      providerName,
      baseUrl: BASE_URL,
      models: `${CUSTOM_PROVIDER}/${providerName}/${MODEL}`,
      apiKey,
    });
    if (created.status !== 201 || !created.id) {
      throw new Error(
        `[staticKeyProvider fixture] create returned ${created.status}: ${created.message}`,
      );
    }

    // Same discriminator argument as above, inverted: the mode-switch tests are
    // about a provider that starts with a static key and NO recipe. A provider
    // that already carried one would make "the switch cleared the other side"
    // unfalsifiable.
    const stored = await backendClient.getProviderKey(created.id);
    if (stored?.value.authConfig !== null) {
      throw new Error(
        `[staticKeyProvider fixture] provider ${created.id} was created with an auth_config: ` +
          `${JSON.stringify(stored?.value.authConfig)}`,
      );
    }

    const ref: StaticKeyProviderRef = { id: created.id, providerName, apiKey };
    await testInfo.attach('opik.staticKeyProvider', {
      body: JSON.stringify({ ...ref, apiKey: '<redacted>' }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      await deleteQuietly(backendClient, created.id, 'staticKeyProvider');
    }
  },
});

export { expect } from './automation-rules.fixture';
