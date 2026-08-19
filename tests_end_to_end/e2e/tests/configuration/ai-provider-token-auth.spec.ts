import { test, expect } from '@e2e/fixtures';
import { AUTH_SECRET_SENTINEL } from '@e2e/core/backend';
import { ConfigurationPage, type CredentialRowState } from '@e2e/pom/configuration.page';

/**
 * A custom AI provider can authenticate with a static API key or by fetching a
 * short-lived token from an OAuth2 client-credentials endpoint. The credentials
 * of the second mode are **write-only**: they are encrypted at rest, read back
 * as a sentinel, and a save that doesn't retype them must keep what is stored.
 *
 * Both tests write through one surface and read back through the other, because
 * the failures worth catching live in the gap between them:
 *  - a secret rendered in clear, or an `api_key` stored alongside `auth_config`,
 *    is a security regression the dialog alone would never show;
 *  - saving the literal sentinel over a real credential breaks every model call
 *    in the workspace and looks completely normal on screen.
 */

const TOKEN_URL = 'https://auth.example.invalid/oauth/token';
const CLIENT_ID = 'e2e-client-id';
const CLIENT_SECRET = 'e2e-client-secret';
const SCOPE_KEY = 'scope';

/** Injected by the dialog on save; the only grant the UI offers. */
const GRANT_TYPE_CREDENTIAL = {
  key: 'grant_type',
  value: 'client_credentials',
  secret: false,
};

/** How a credential the user can still read and edit renders. */
function clearRow(key: string, value: string): CredentialRowState {
  return {
    key,
    value,
    placeholder: 'Value',
    locked: false,
    lockToggleDisabled: false,
    revealable: false,
    masked: false,
  };
}

/** How a credential that is only stored renders: no value, no way to read it. */
function storedSecretRow(key: string): CredentialRowState {
  return {
    key,
    value: AUTH_SECRET_SENTINEL,
    placeholder: 'stored, write-only',
    locked: true,
    lockToggleDisabled: true,
    revealable: false,
    masked: true,
  };
}

test.describe(
  'AI providers — OAuth2 client credentials',
  { tag: ['@t2-cuj', '@area:configuration'] },
  () => {
    test(
      'A provider added in OAuth2 mode stores no API key and reads its secret back write-only',
      { tag: ['@cap:configuration.ai-provider-add'] },
      async ({ aiProviders, backendClient, page }) => {
        const providerName = aiProviders.name('uitoken');
        const configuration = new ConfigurationPage(page);

        await test.step('Open a blank Custom provider configuration', async () => {
          await configuration.gotoAiProviders();
          await configuration.openAddCustomProviderDialog();
          await configuration.fillCustomProviderBasics({
            providerName,
            baseUrl: 'https://vllm.example.invalid/v1',
            models: 'model-a,model-b',
          });
        });

        await test.step('Switching to OAuth2 seeds the two client-credentials rows', async () => {
          await configuration.selectAuthMode('OAuth2 client credentials');
          // client_secret arrives already locked, and offers a reveal while it
          // is a value the user typed rather than one the backend holds.
          expect(await configuration.readCredentialRows()).toEqual([
            clearRow('client_id', ''),
            {
              key: 'client_secret',
              value: '',
              placeholder: 'Value',
              locked: true,
              lockToggleDisabled: false,
              revealable: true,
              masked: true,
            },
          ]);
        });

        const submitted = await test.step('Fill the recipe and save', async () => {
          await configuration.fillTokenUrl(TOKEN_URL);
          await configuration.fillCredentialValue('client_id', CLIENT_ID);
          await configuration.fillCredentialValue('client_secret', CLIENT_SECRET);
          await configuration.addCredential({ key: SCOPE_KEY, value: 'models.read' });
          return configuration.submitProviderDialog('create');
        });

        await test.step('The create carries the whole recipe and no api_key at all', async () => {
          expect(submitted.status).toBe(201);
          // Not "api_key is empty": the backend rejects the two being set
          // together, so the dialog must not send the key in any form.
          expect(Object.keys(submitted.payload)).not.toContain('api_key');
          expect(submitted.payload.auth_config).toMatchObject({
            token_url: TOKEN_URL,
            credentials: [
              GRANT_TYPE_CREDENTIAL,
              { key: 'client_id', value: CLIENT_ID, secret: false },
              { key: 'client_secret', value: CLIENT_SECRET, secret: true },
              { key: SCOPE_KEY, value: 'models.read', secret: false },
            ],
          });
        });

        const stored = await test.step('Read the stored record back', async () => {
          const providers = await aiProviders.list();
          expect(providers.map((provider) => provider.providerName)).toEqual([providerName]);
          const record = await backendClient.getLlmProviderKey(providers[0].id);
          expect(record).not.toBeNull();
          return record!;
        });

        await test.step('Only the secret is masked, and no api_key was stored', async () => {
          expect(stored.hasApiKeyField).toBe(false);
          expect(stored.authConfig).not.toBeNull();
          expect(stored.authConfig!.tokenUrl).toBe(TOKEN_URL);
          // The whole credential list, so a value silently added or dropped
          // between the dialog and the store fails here.
          expect(stored.authConfig!.credentials).toEqual([
            GRANT_TYPE_CREDENTIAL,
            { key: 'client_id', value: CLIENT_ID, secret: false },
            { key: 'client_secret', value: AUTH_SECRET_SENTINEL, secret: true },
            { key: SCOPE_KEY, value: 'models.read', secret: false },
          ]);
        });

        await test.step('Reopening the dialog offers no way to read the secret back', async () => {
          await configuration.gotoAiProviders();
          await configuration.searchForProvider(providerName);
          await configuration.openEditDialogFor(providerName);
          // grant_type is the dialog's own injection, so it is hidden on load;
          // everything the user entered comes back, and only the secret is
          // reduced to the sentinel.
          expect(await configuration.readCredentialRows()).toEqual([
            clearRow('client_id', CLIENT_ID),
            storedSecretRow('client_secret'),
            clearRow(SCOPE_KEY, 'models.read'),
          ]);
        });
      },
    );

    test(
      'Editing without retyping the secret keeps the stored one and applies the other changes',
      { tag: ['@cap:configuration.ai-provider-edit'] },
      async ({ aiProviders, backendClient, page }) => {
        const providerName = aiProviders.name('edit');
        const qualified = (...models: string[]) =>
          models.map((model) => `custom-llm/${providerName}/${model}`).join(',');

        const provider = await aiProviders.seed({
          suffix: 'edit',
          // Models are stored provider-qualified; the dialog strips the prefix
          // to show them and re-applies it on save.
          configuration: { models: qualified('model-a') },
          authConfig: {
            token_url: TOKEN_URL,
            // Recipe fields the dialog never renders. A UI edit must carry them
            // through untouched rather than reset them to the OAuth2 defaults.
            send_as: 'form',
            token_field: 'data.access_token',
            expires_field: 'data.expires_in',
            fallback_ttl_seconds: 600,
            credentials: [
              { key: 'client_id', value: CLIENT_ID, secret: false },
              { key: 'client_secret', value: CLIENT_SECRET, secret: true },
              { key: SCOPE_KEY, value: 'models.read', secret: false },
            ],
          },
        });

        await test.step('The seeded provider really is in token mode with a stored secret', async () => {
          const seeded = await backendClient.getLlmProviderKey(provider.id);
          expect(seeded).not.toBeNull();
          expect(seeded!.hasApiKeyField).toBe(false);
          expect(seeded!.authConfig).not.toBeNull();
          expect(seeded!.authConfig!.credentials).toEqual([
            { key: 'client_id', value: CLIENT_ID, secret: false },
            { key: 'client_secret', value: AUTH_SECRET_SENTINEL, secret: true },
            { key: SCOPE_KEY, value: 'models.read', secret: false },
          ]);
        });

        const configuration = new ConfigurationPage(page);

        await test.step('Open it for editing', async () => {
          await configuration.gotoAiProviders();
          await configuration.searchForProvider(provider.providerName);
          await configuration.openEditDialogFor(provider.providerName);
          expect(await configuration.readCredentialRows()).toEqual([
            clearRow('client_id', CLIENT_ID),
            storedSecretRow('client_secret'),
            clearRow(SCOPE_KEY, 'models.read'),
          ]);
        });

        const submitted = await test.step('Change the models and the scope, leave the secret alone', async () => {
          await configuration.fillCustomProviderBasics({ models: 'model-a,model-c' });
          await configuration.fillCredentialValue(SCOPE_KEY, 'models.write');
          return configuration.submitProviderDialog('update');
        });

        await test.step('The update is accepted and asks to keep the stored secret', async () => {
          expect(submitted.status).toBe(204);
          expect(submitted.payload.auth_config).toMatchObject({
            credentials: [
              GRANT_TYPE_CREDENTIAL,
              { key: 'client_id', value: CLIENT_ID, secret: false },
              // The sentinel means "keep what is stored". Sending it is only
              // safe because the backend resolves it; a 400 here would mean the
              // dialog had to make the user retype the secret.
              { key: 'client_secret', value: AUTH_SECRET_SENTINEL, secret: true },
              { key: SCOPE_KEY, value: 'models.write', secret: false },
            ],
          });
        });

        await test.step('The edits landed and nothing else moved', async () => {
          const updated = await backendClient.getLlmProviderKey(provider.id);
          expect(updated).not.toBeNull();
          // Qualified once, not twice: the prefix the dialog stripped on load
          // is the one it puts back.
          expect(updated!.configuration.models).toBe(qualified('model-a', 'model-c'));
          expect(updated!.hasApiKeyField).toBe(false);
          expect(updated!.authConfig).not.toBeNull();
          expect(updated!.authConfig!.credentials).toEqual([
            GRANT_TYPE_CREDENTIAL,
            { key: 'client_id', value: CLIENT_ID, secret: false },
            { key: 'client_secret', value: AUTH_SECRET_SENTINEL, secret: true },
            { key: SCOPE_KEY, value: 'models.write', secret: false },
          ]);
          expect(updated!.authConfig!.sendAs).toBe('form');
          expect(updated!.authConfig!.tokenField).toBe('data.access_token');
          expect(updated!.authConfig!.expiresField).toBe('data.expires_in');
          expect(updated!.authConfig!.fallbackTtlSeconds).toBe(600);
        });
      },
    );
  },
);

/*
 * Not asserted here: that the *value* still stored after the edit is the
 * original secret rather than the literal sentinel. Both read back identically
 * through the API by design, so telling them apart needs an auth service that
 * echoes what it was sent — a third-party dependency this suite deliberately
 * avoids. The exploration this spec came from proved it by hand with one.
 */
