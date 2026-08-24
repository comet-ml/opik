import { test, expect } from '@e2e/fixtures';
import type { ProviderAuthCredentialRef } from '@e2e/core/backend';
import { ConfigurationPage } from '@e2e/pom/configuration.page';

/**
 * Dynamic token auth (OAuth2 client credentials) for custom AI providers —
 * OPIK-7940 / opik#7910.
 *
 * The two behaviours covered here are the ones that fail *silently*. A provider
 * whose stored client secret was dropped, blanked or unflagged by a save still
 * renders as a correctly configured row on the AI providers tab; the first
 * evidence of the loss is a model call failing, far from the screen that caused
 * it. Likewise, a provider left holding both a static `api_key` and a token
 * recipe has no page that shows the conflict.
 *
 * Both are asserted on both surfaces, which is the point: the recipe is written
 * through the Edit dialog and read back through the API, so a disagreement
 * between what the dialog sends and what the backend stores is what fails the
 * test. The dialog's own request body is captured rather than inferred — the
 * secret-preserving `__SECRET__` sentinel and the clear-the-other-mode payloads
 * are decided there, and a dialog that sent the wrong one would still close
 * cleanly and leave a table that looked right.
 *
 * SCOPE — nothing here performs a token fetch. Opik's backend, not the test
 * runner, calls the auth service, so a success path needs an auth service the
 * *backend container* can reach, which this estate has no fixture for. Two
 * consequences, both deliberate:
 *   - the recipes point at an RFC 2606 `.invalid` host that can never resolve,
 *     so the tests never depend on egress;
 *   - a secret silently replaced by an EMPTY string is not detected. The
 *     assertions below catch a dropped credential and a flipped secret flag,
 *     because both change what the API reads back; a blanked value still reads
 *     back as the sentinel. Distinguishing it needs a real token fetch, whose
 *     error message discriminates ("could not reach …" while the secret is
 *     intact, "requires 'client_id' and 'client_secret' credentials" once it is
 *     gone) — but only where the SSRF destination guard runs `relaxed`, which
 *     is the docker-compose/Helm default and NOT the application default that
 *     cloud runs. Left out rather than made conditional on the deployment.
 */

/** The backend's write-only marker: a stored secret reads back as this, never in the clear. */
const SECRET_SENTINEL = '__SECRET__';

/** `grant_type` is injected by the form and hidden from the credentials list, by design. */
const HIDDEN_GRANT_TYPE_ROW = { key: 'grant_type', value: 'client_credentials', secret: false };

/** Credentials as they must read back once stored: id in the clear, secret masked. */
const expectedStoredCredentials = (clientId: string): ProviderAuthCredentialRef[] => [
  HIDDEN_GRANT_TYPE_ROW,
  { key: 'client_id', value: clientId, secret: false },
  { key: 'client_secret', value: SECRET_SENTINEL, secret: true },
];

test.describe(
  'Configuration — AI provider dynamic token auth',
  { tag: ['@t2-cuj', '@area:configuration'] },
  () => {
    test(
      'A stored OAuth2 client secret reads back masked and survives an untouched re-save',
      { tag: ['@cap:configuration.ai-provider-edit'] },
      async ({ tokenAuthProvider, backendClient, page }) => {
        const { id, providerName, tokenUrl, clientId, clientSecret } = tokenAuthProvider;

        await test.step('Both reads mask the secret, and neither body carries the plaintext', async () => {
          const byId = await backendClient.getProviderKey(id);
          expect(byId, `provider ${id} is readable`).not.toBeNull();
          expect(byId!.value.authConfig, 'the provider holds a token recipe').not.toBeNull();
          expect(
            byId!.value.authConfig!.credentials,
            'every credential survives the read, with the secret masked and the id in the clear',
          ).toEqual(expectedStoredCredentials(clientId));
          expect(
            byId!.rawBody,
            'the by-id response carries no plaintext secret in any field',
          ).not.toContain(clientSecret);

          const list = await backendClient.listProviderKeys();
          // Scoped to our own row, not to the whole list: the workspace is
          // shared with other workers. The leak assertion below is the one that
          // covers the whole payload.
          const ours = list.value.filter((provider) => provider.id === id);
          expect(ours, `the list carries provider ${id} exactly once`).toHaveLength(1);
          expect(
            ours[0].authConfig?.credentials,
            'the list masks the secret the same way the by-id read does',
          ).toEqual(expectedStoredCredentials(clientId));
          expect(
            list.rawBody,
            'no row of the list response carries the plaintext secret',
          ).not.toContain(clientSecret);
        });

        const configuration = new ConfigurationPage(page);
        const dialog = await test.step('Open the provider in the Edit dialog', async () => {
          await configuration.gotoAiProviders();
          return configuration.openEditDialogForCustomProvider(providerName, id);
        });

        await test.step('The dialog reopens in token mode with the secret write-only', async () => {
          await expect(dialog.tokenModeRadio, 'the saved auth mode is restored').toBeChecked();
          await expect(dialog.staticModeRadio).not.toBeChecked();
          await expect(dialog.tokenUrlInput).toHaveValue(tokenUrl);

          await expect(
            dialog.credentialKeyInputs,
            'client_id and client_secret are listed; the injected grant_type row is hidden',
          ).toHaveCount(2);
          await expect(dialog.credentialKeyInputs.nth(0)).toHaveValue('client_id');
          await expect(dialog.credentialKeyInputs.nth(1)).toHaveValue('client_secret');

          await expect(
            dialog.storedSecretInput,
            'exactly one credential renders as a stored, write-only secret',
          ).toHaveCount(1);
          await expect(
            dialog.storedSecretInput,
            'the stored secret is not pre-filled into the form',
          ).toHaveValue('');

          expect(
            await dialog.visibleText(),
            'the dialog renders the secret nowhere, not even in a hidden-looking field',
          ).not.toContain(clientSecret);
        });

        const update = await test.step('Save the dialog without touching anything', async () =>
          dialog.saveAndCaptureUpdate());

        await test.step('The untouched save sends the keep-the-stored-secret sentinel', async () => {
          expect(update.status, 'the update is accepted').toBe(204);
          expect(
            update.body.auth_config?.credentials,
            'the request re-sends every credential, the secret as the sentinel rather than a value',
          ).toEqual(expectedStoredCredentials(clientId));
          expect(
            update.body.api_key,
            'token mode clears the static key in the same request',
          ).toBe('');
        });

        await test.step('The stored recipe is unchanged by the round-trip', async () => {
          const after = await backendClient.getProviderKey(id);
          expect(after, `provider ${id} still exists`).not.toBeNull();
          expect(
            after!.value.authConfig?.tokenUrl,
            'the token URL is unchanged',
          ).toBe(tokenUrl);
          expect(
            after!.value.authConfig?.credentials,
            'no credential was dropped and no secret flag was flipped by the re-save',
          ).toEqual(expectedStoredCredentials(clientId));
          expect(
            after!.rawBody,
            'the secret is still write-only after the round-trip',
          ).not.toContain(clientSecret);
        });
      },
    );

    test(
      'A provider holds either a static API key or a token recipe, and switching clears the other',
      { tag: ['@cap:configuration.ai-provider-edit'] },
      async ({ staticKeyProvider, backendClient, page, testNamespace }) => {
        const { id, providerName, apiKey } = staticKeyProvider;

        const uiClientId = `${testNamespace}-ui-client-id`;
        const uiClientSecret = `${testNamespace}-ui-secret`;
        const uiTokenUrl = 'https://auth.opik-e2e-does-not-exist.invalid/ui/token';
        const replacementApiKey = `${testNamespace}-replacement-key`;

        const recipe = {
          token_url: 'https://auth.opik-e2e-does-not-exist.invalid/api/token',
          send_as: 'basic',
          credentials: [
            { key: 'client_id', value: `${testNamespace}-api-client-id`, secret: false },
            { key: 'client_secret', value: `${testNamespace}-api-secret`, secret: true },
          ],
        };

        await test.step('Setting a static key and a token recipe together is refused', async () => {
          const conflict = await backendClient.updateProviderKey({
            id,
            apiKey: 'should-never-be-stored',
            authConfig: recipe,
          });
          expect(conflict.status, 'the conflicting update is rejected').toBe(400);
          expect(conflict.message, 'the rejection names api_key').toContain('api_key');
          expect(conflict.message, 'the rejection names auth_config').toContain('auth_config');

          // A refusal that had partially applied would be worse than one that
          // applied cleanly, so assert the provider is untouched, not just that
          // the status was 400.
          const unchanged = await backendClient.getProviderKey(id);
          expect(
            unchanged!.value.authConfig,
            'the refused update stored no recipe',
          ).toBeNull();
        });

        await test.step('A recipe on its own clears the stored static key', async () => {
          const switched = await backendClient.updateProviderKey({ id, authConfig: recipe });
          expect(switched.status, `switching to token auth: ${switched.message}`).toBe(204);

          const read = await backendClient.getProviderKey(id);
          expect(read!.value.apiKey, 'the static key is cleared, not merely hidden').toBe('');
          expect(
            read!.value.authConfig?.credentials,
            'the recipe is stored with the secret masked',
          ).toEqual([
            { key: 'client_id', value: recipe.credentials[0].value, secret: false },
            { key: 'client_secret', value: SECRET_SENTINEL, secret: true },
          ]);
          expect(read!.rawBody, 'the API-written secret does not read back').not.toContain(
            recipe.credentials[1].value,
          );
        });

        await test.step('An empty recipe plus a new key switches back to static auth', async () => {
          const switched = await backendClient.updateProviderKey({
            id,
            apiKey: replacementApiKey,
            authConfig: {},
          });
          expect(switched.status, `switching back to a static key: ${switched.message}`).toBe(204);

          const read = await backendClient.getProviderKey(id);
          expect(read!.value.authConfig, 'the recipe is gone').toBeNull();
          expect(read!.value.apiKey, 'a static key is stored again').not.toBe('');
          expect(read!.value.apiKey, 'and it is masked, never returned in the clear').not.toBe(
            replacementApiKey,
          );
          expect(read!.rawBody).not.toContain(replacementApiKey);
        });

        await test.step('Testing a provider that now holds no recipe is refused', async () => {
          const tested = await backendClient.testProviderAuthConfig({ providerId: id });
          expect(tested.status).toBe(400);
          expect(tested.message).toContain('no auth_config to test');
        });

        const configuration = new ConfigurationPage(page);
        await configuration.gotoAiProviders();

        const toToken = await test.step('Switch the provider to token auth through the dialog', async () => {
          const dialog = await configuration.openEditDialogForCustomProvider(providerName, id);
          await expect(
            dialog.staticModeRadio,
            'the dialog opens in the mode the provider was last saved in',
          ).toBeChecked();
          await expect(dialog.apiKeyInput, 'static mode offers the API key field').toHaveCount(1);

          await dialog.selectTokenMode();
          await expect(
            dialog.apiKeyInput,
            'token mode hides the API key field entirely — the two cannot be set together',
          ).toHaveCount(0);

          await dialog.fillOauth2Recipe({
            tokenUrl: uiTokenUrl,
            clientId: uiClientId,
            clientSecret: uiClientSecret,
          });
          return dialog.saveAndCaptureUpdate();
        });

        await test.step('The dialog sent the recipe and cleared the static key in one request', async () => {
          expect(toToken.status).toBe(204);
          expect(toToken.body.api_key, 'the static key is cleared by the same save').toBe('');
          expect(toToken.body.auth_config?.token_url).toBe(uiTokenUrl);
          expect(
            toToken.body.auth_config?.credentials,
            'the form injects the grant_type row alongside the two the user filled',
          ).toEqual([
            HIDDEN_GRANT_TYPE_ROW,
            { key: 'client_id', value: uiClientId, secret: false },
            { key: 'client_secret', value: uiClientSecret, secret: true },
          ]);

          const read = await backendClient.getProviderKey(id);
          expect(read!.value.apiKey, 'the backend agrees the static key is gone').toBe('');
          expect(read!.value.authConfig?.credentials).toEqual(
            expectedStoredCredentials(uiClientId),
          );
          expect(read!.rawBody, 'the secret typed into the dialog does not read back').not.toContain(
            uiClientSecret,
          );
        });

        const toStatic = await test.step('Switch it back to a static key through the dialog', async () => {
          const dialog = await configuration.openEditDialogForCustomProvider(providerName, id);
          await expect(
            dialog.tokenModeRadio,
            'the dialog reopens in token mode, the mode last saved',
          ).toBeChecked();

          await dialog.selectStaticMode();
          await dialog.fillApiKey(replacementApiKey);
          return dialog.saveAndCaptureUpdate();
        });

        await test.step('The dialog sent the clear-the-recipe payload with the new key', async () => {
          expect(toStatic.status).toBe(204);
          expect(
            toStatic.body.auth_config,
            'an empty object is the API convention for clearing the recipe',
          ).toEqual({});
          expect(toStatic.body.api_key).toBe(replacementApiKey);

          const read = await backendClient.getProviderKey(id);
          expect(read!.value.authConfig, 'the recipe is gone again').toBeNull();
          expect(read!.value.apiKey, 'the new static key is stored').not.toBe('');
          expect(read!.rawBody).not.toContain(replacementApiKey);
        });

        await test.step('Reopening shows the mode that was last saved', async () => {
          const dialog = await configuration.openEditDialogForCustomProvider(providerName, id);
          await expect(dialog.staticModeRadio).toBeChecked();
          await expect(dialog.tokenModeRadio).not.toBeChecked();
        });
      },
    );
  },
);
