import { test, expect } from '@e2e/fixtures';
import type { ProviderAuthConfigRef, ProviderCredentialRef } from '@e2e/core/backend';

/**
 * The dynamic-token-auth contract for custom AI providers — OPIK-7940 / opik#7910.
 *
 * A provider can now authenticate either with a static `api_key` or with an
 * OAuth2 client-credentials recipe (`auth_config`) the backend runs itself. Two
 * properties of that recipe are what a human would care about being wrong, and
 * neither surfaces as an error when it breaks:
 *
 *   1. **A saved secret never reads back.** It answers as the `__SECRET__`
 *      sentinel, and the browser must never see the stored value. A regression
 *      here is a credential leak that every page renders perfectly happily.
 *   2. **The sentinel means "keep the stored secret", and only ever that.** The
 *      dialog re-submits `__SECRET__` for a row the user did not retype, so the
 *      backend must resolve it against what is stored — and must refuse it where
 *      nothing is stored under that key, or a literal `__SECRET__` would quietly
 *      become the credential.
 *
 * SURFACE — API, deliberately. Everything asserted here is a property of the
 * request/response contract, and the dialog observes it only second-hand: the
 * masked read-back is invisible in the UI (the secret field renders empty either
 * way), and a rejected write reaches the user as a toast whose text is exactly
 * the `errors`/`message` body asserted below. Driving a browser to observe any
 * of it would be slower, flakier, and would assert less.
 *
 * SCOPE — what this file does NOT cover, so its green is not read as more than
 * it is: that the *stored value* is still the working secret after a re-save can
 * only be proven by fetching a token, which needs an OAuth2 token endpoint the
 * backend under test can reach. There is none on this environment. What is
 * proven instead is that the sentinel resolves against the stored recipe and is
 * refused where it cannot — the discriminating half of the same rule.
 */

/**
 * The credential row under `key`, asserted to be there and unique.
 *
 * Not `credentials.find(...)`: an absent row would make every comparison below
 * vacuous, and a duplicated key would silently test whichever copy came first.
 */
const credential = (
  authConfig: ProviderAuthConfigRef,
  key: string,
): ProviderCredentialRef => {
  const matches = authConfig.credentials.filter((row) => row.key === key);
  expect(matches, `auth_config carries exactly one "${key}" credential`).toHaveLength(1);
  return matches[0];
};

/** A minimal recipe that passes validation; individual tests break one field of it. */
const validRecipe = (credentials: Array<Record<string, unknown>>) => ({
  token_url: 'https://auth.example.invalid/oauth/token',
  send_as: 'basic',
  credentials,
  token_field: 'access_token',
  expires_field: 'expires_in',
  fallback_ttl_seconds: 60,
});

const SENTINEL = '__SECRET__';

test.describe('Configuration — AI provider dynamic token auth', {
  tag: ['@t2-cuj', '@area:configuration'],
}, () => {
  test(
    'an auth_config the backend cannot accept is refused with a body that names the rule',
    { tag: ['@cap:configuration.ai-provider-add'] },
    async ({ backendClient, aiProviders }) => {
      // Every rejection below happens before any outbound fetch, so this test
      // needs no auth service and cannot be flaky on one.
      const name = (suffix: string) => `${aiProviders.namePrefix}-${suffix}`;

      await test.step('api_key and auth_config are mutually exclusive', async () => {
        const { status, errors } = await backendClient.createProviderKey({
          provider: 'custom-llm',
          provider_name: name('both'),
          base_url: 'https://example.invalid/v1',
          api_key: 'sk-static-key',
          auth_config: validRecipe([{ key: 'client_id', value: 'cid', secret: false }]),
        });
        expect(status, 'a provider carrying both auth modes').toBe(422);
        expect(errors.join(' '), 'the rejection names api_key').toContain('api_key');
      });

      await test.step('token_url must be an http(s) URL', async () => {
        const { status, errors } = await backendClient.createProviderKey({
          provider: 'custom-llm',
          provider_name: name('ftp'),
          base_url: 'https://example.invalid/v1',
          auth_config: {
            ...validRecipe([{ key: 'client_id', value: 'cid', secret: false }]),
            token_url: 'ftp://auth.example.invalid/token',
          },
        });
        expect(status, 'a non-http token_url').toBe(422);
        expect(errors.join(' '), 'the rejection names the accepted schemes').toContain(
          'http or https',
        );
      });

      await test.step('a recipe with no credentials is refused', async () => {
        const { status, errors } = await backendClient.createProviderKey({
          provider: 'custom-llm',
          provider_name: name('nocreds'),
          base_url: 'https://example.invalid/v1',
          auth_config: validRecipe([]),
        });
        expect(status, 'an empty credentials list').toBe(422);
        expect(errors.join(' '), 'the rejection names credentials').toContain('credentials');
      });

      await test.step('the secret sentinel is meaningless on create and is refused', async () => {
        // Nothing is stored yet for it to mean "keep", so accepting it would
        // store the literal string as the credential.
        const { status, errors } = await backendClient.createProviderKey({
          provider: 'custom-llm',
          provider_name: name('sentinel'),
          base_url: 'https://example.invalid/v1',
          auth_config: validRecipe([{ key: 'client_secret', value: SENTINEL, secret: true }]),
        });
        expect(status, 'the sentinel as a value on create').toBe(422);
        expect(errors.join(' '), 'the rejection names the sentinel').toContain(SENTINEL);
      });

      await test.step('a provider without a provider_name cannot carry a recipe', async () => {
        const { status, errors } = await backendClient.createProviderKey({
          provider: 'openai',
          api_key: 'sk-static-key',
          auth_config: validRecipe([{ key: 'client_id', value: 'cid', secret: false }]),
        });
        expect(status, 'auth_config on a first-party provider').toBe(422);
        expect(errors.join(' '), 'the rejection names the provider_name requirement').toContain(
          'provider_name',
        );
      });

      await test.step('the test-connection endpoint refuses a request naming neither', async () => {
        // This one answers in the other error shape — 400 {"message": ...} —
        // which the dialog reads through a different branch of its handler.
        const { status, message } = await backendClient.testProviderAuthConfig({});
        expect(status, 'a test-connection request with an empty body').toBe(400);
        expect(message, 'the rejection says what was missing').toBe(
          'either provider_id or auth_config must be provided',
        );
      });

      await test.step('none of the refused payloads created a provider', async () => {
        // Without this, every assertion above would also hold for a backend that
        // answered 422 *and* wrote the row anyway.
        const configured = await backendClient.listProviderKeys();
        const leaked = configured.filter((provider) =>
          provider.providerName?.startsWith(aiProviders.namePrefix),
        );
        expect(leaked, 'the workspace gained no provider from a rejected write').toHaveLength(0);
      });
    },
  );

  test(
    'a saved secret reads back masked and the sentinel keeps it in place across a re-save',
    {
      tag: ['@cap:configuration.ai-provider-add', '@cap:configuration.ai-provider-edit'],
    },
    async ({ backendClient, aiProviders }) => {
      const CLIENT_ID = 'client-id-not-a-secret';
      const CLIENT_SECRET = 'client-secret-value-never-readable';
      const TOKEN_URL = 'https://auth.example.invalid/oauth/token';

      const provider = await test.step('Create a provider in OAuth2 client-credentials mode', async () =>
        aiProviders.createCustom({
          authConfig: validRecipe([
            { key: 'client_id', value: CLIENT_ID, secret: false },
            { key: 'client_secret', value: CLIENT_SECRET, secret: true },
            { key: 'scope', value: 'models.read', secret: false },
          ]),
        }));

      await test.step('The read masks the secret and returns everything else verbatim', async () => {
        const stored = await backendClient.getProviderKey(provider.id);

        expect(stored.authConfig, 'the provider carries the recipe it was created with').not.toBeNull();
        const authConfig = stored.authConfig!;

        expect(credential(authConfig, 'client_secret').value, 'the stored secret').toBe(SENTINEL);
        expect(credential(authConfig, 'client_secret').secret, 'client_secret stays flagged').toBe(
          true,
        );
        // The non-secret rows are the control: if the read masked everything,
        // the assertion above would pass while proving nothing about secrecy.
        expect(credential(authConfig, 'client_id').value, 'a non-secret credential').toBe(CLIENT_ID);
        expect(credential(authConfig, 'scope').value, 'a non-secret credential').toBe('models.read');
        expect(authConfig.tokenUrl, 'token_url').toBe(TOKEN_URL);
        expect(authConfig.sendAs, 'send_as').toBe('basic');
        expect(authConfig.tokenField, 'token_field').toBe('access_token');
        expect(authConfig.expiresField, 'expires_field').toBe('expires_in');
        expect(authConfig.fallbackTtlSeconds, 'fallback_ttl_seconds').toBe(60);

        // A token-auth provider has no static key at all — the two modes are
        // mutually exclusive in storage, not merely on the write path.
        expect(stored.apiKey, 'a token-auth provider carries no api_key').toBeNull();
      });

      await test.step('The list read masks it too, not only the by-id read', async () => {
        // The dialog is populated from the list, so a mask applied on only one
        // of the two reads would still hand the secret to the browser.
        const listed = (await backendClient.listProviderKeys()).filter(
          (row) => row.providerName === provider.providerName,
        );
        expect(listed, 'the provider appears exactly once in the workspace list').toHaveLength(1);
        expect(listed[0].authConfig, 'the listed row carries the recipe').not.toBeNull();
        expect(
          credential(listed[0].authConfig!, 'client_secret').value,
          'the secret as the list answers it',
        ).toBe(SENTINEL);
      });

      await test.step('Re-saving with the sentinel is accepted and lands the other edits', async () => {
        // This is what the dialog submits when the user changes something else
        // and does not retype the secret: the sentinel comes straight back.
        const { status, message } = await backendClient.updateProviderKey(provider.id, {
          auth_config: validRecipe([
            { key: 'client_id', value: CLIENT_ID, secret: false },
            { key: 'client_secret', value: SENTINEL, secret: true },
            { key: 'scope', value: 'models.write', secret: false },
          ]),
        });
        expect(status, `re-save rejected with: ${message}`).toBe(204);

        const stored = await backendClient.getProviderKey(provider.id);
        expect(stored.authConfig, 'the recipe survived the update').not.toBeNull();
        expect(credential(stored.authConfig!, 'scope').value, 'the edit that was made').toBe(
          'models.write',
        );
        expect(
          credential(stored.authConfig!, 'client_secret').value,
          'the secret still reads masked',
        ).toBe(SENTINEL);
      });

      await test.step('The sentinel is refused where no stored secret sits under that key', async () => {
        // The rule that makes the sentinel safe: it is resolved against the
        // stored recipe key-by-key, never taken on trust. Without this, a
        // backend that accepted any `__SECRET__` and wrote it through would
        // pass every assertion above.
        const { status, message } = await backendClient.updateProviderKey(provider.id, {
          auth_config: validRecipe([
            { key: 'client_id', value: SENTINEL, secret: false },
            { key: 'client_secret', value: SENTINEL, secret: true },
          ]),
        });
        expect(status, 'the sentinel under a non-secret key').toBe(400);
        expect(message, 'the rejection names the offending credential').toContain('client_id');

        const stored = await backendClient.getProviderKey(provider.id);
        expect(
          credential(stored.authConfig!, 'client_id').value,
          'the refused update changed nothing',
        ).toBe(CLIENT_ID);
      });

      await test.step('A saved secret cannot be downgraded to a readable field', async () => {
        // Sending the row back with secret:false is how an unlocked padlock
        // would submit. Honouring it would make the next read hand the value
        // to the browser.
        const { status, message } = await backendClient.updateProviderKey(provider.id, {
          auth_config: validRecipe([
            { key: 'client_id', value: CLIENT_ID, secret: false },
            { key: 'client_secret', value: 'attempt-to-unlock', secret: false },
            { key: 'scope', value: 'models.write', secret: false },
          ]),
        });
        expect(status, `downgrade rejected with: ${message}`).toBe(204);

        const stored = await backendClient.getProviderKey(provider.id);
        const secretRow = credential(stored.authConfig!, 'client_secret');
        expect(secretRow.secret, 'client_secret is still flagged secret').toBe(true);
        expect(secretRow.value, 'client_secret still reads masked').toBe(SENTINEL);
      });

      await test.step('A static api_key cannot be added alongside the stored recipe', async () => {
        const { status, message } = await backendClient.updateProviderKey(provider.id, {
          api_key: 'sk-static-key',
        });
        expect(status, 'an api_key on a token-auth provider').toBe(400);
        expect(message, 'the rejection names both modes').toContain('auth_config');

        const stored = await backendClient.getProviderKey(provider.id);
        // Absent and empty are the same answer by this point, and deliberately
        // not distinguished: a create in token mode omits `api_key` entirely,
        // while a later successful `auth_config` update normalises the column
        // to "". Both read as "no static key" everywhere the backend consults
        // it (every such read is guarded by `isNotBlank`). What must never be
        // true is that the refused write left a usable key behind — which is a
        // non-empty masked string, so this comparison still fails on it.
        expect(stored.apiKey ?? '', 'the refused update stored no usable key').toBe('');
        expect(stored.authConfig, 'the recipe is untouched').not.toBeNull();
      });
    },
  );
});
