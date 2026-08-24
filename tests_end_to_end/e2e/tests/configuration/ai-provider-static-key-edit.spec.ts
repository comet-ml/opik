import { test, expect } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';

/**
 * Editing a static-key custom AI provider must not disturb its stored API key —
 * OPIK-7940 / opik#7910.
 *
 * Why this is worth a permanent test. Adding OAuth2 token auth made the update
 * dialog able to *clear* a stored key: switching a provider to token mode sends
 * `api_key: ""`, so the update mutation's guard had to move from a truthiness
 * check to `apiKey !== undefined`. That guard now sits one step away from a
 * silent credential wipe — if the dialog ever passes `""` for an untouched key
 * field, an unrelated edit deletes the key and nothing anywhere says so. The API
 * key input always renders empty when the dialog reopens (the stored key never
 * reads back), so there is no version of this page on which a user could notice.
 *
 * SURFACE — both, as the exploration classified it, and both halves are load-
 * bearing. The regression lives in the browser, in what the dialog chooses to
 * put in the PATCH, so the edit is driven through the real UI and the request it
 * emits is asserted directly. Whether the key actually survived is not something
 * any page renders, so that half is read back through the API — the masked form
 * is stable, so "unchanged" is a byte comparison rather than an inference.
 *
 * The provider is seeded through the API rather than click-created: creating one
 * is not what this test exercises, and a UI seed would make a failure ambiguous
 * about which of the two dialogs broke.
 */

const SEEDED_API_KEY = 'sk-static-0123456789abcdef-242';
const SEEDED_URL = 'https://gateway.example.invalid/v1';
const EDITED_URL = 'https://gateway.example.invalid/v2';

test.describe('Configuration — AI providers', { tag: ['@t2-cuj', '@area:configuration'] }, () => {
  test(
    'editing only the URL of a static-key provider leaves the stored key untouched',
    { tag: ['@cap:configuration.ai-provider-edit'] },
    async ({ page, backendClient, aiProviders }) => {
      const configuration = new ConfigurationPage(page);

      const provider = await test.step('Seed a static-key custom provider', async () =>
        aiProviders.createCustom({ apiKey: SEEDED_API_KEY, baseUrl: SEEDED_URL }));

      const maskedKeyBefore = await test.step('Record the key as the API masks it', async () => {
        const stored = await backendClient.getProviderKey(provider.id);
        // Assert the precondition rather than trusting the seed: if the provider
        // came back with no key at all, every "unchanged" assertion below would
        // hold vacuously and this test could never fail.
        expect(stored.apiKey, 'the seeded provider stores an api_key').not.toBeNull();
        expect(stored.baseUrl, 'the seeded base_url').toBe(SEEDED_URL);
        expect(stored.authConfig, 'a static-key provider carries no auth_config').toBeNull();
        return stored.apiKey!;
      });

      await test.step('The reopened dialog shows no stored key to preserve', async () => {
        await configuration.gotoAiProviders();
        const dialog = await configuration.openEditDialog(provider.providerName);
        // This is the whole hazard in one assertion: the field the user sees is
        // empty, so an edit that submitted its contents would submit nothing.
        await expect(
          configuration.apiKeyField(dialog),
          'the API key field renders empty — the stored key never reads back',
        ).toHaveValue('');
        await page.keyboard.press('Escape');
        await dialog.waitFor({ state: 'hidden' });
      });

      const patchBody = await test.step('Change only the URL and capture what the dialog sends', async () => {
        const patchRequest = page.waitForRequest(
          (request) =>
            request.method() === 'PATCH' &&
            request.url().includes(`/v1/private/llm-provider-key/${provider.id}`),
        );
        await configuration.editCustomProviderUrl(provider.providerName, EDITED_URL);
        return (await patchRequest).postDataJSON() as Record<string, unknown>;
      });

      await test.step('The request carries the new URL and no api_key property at all', async () => {
        expect(patchBody.base_url, 'the edit that was made').toBe(EDITED_URL);
        // `not.toHaveProperty`, not a falsy check: `api_key: ""` is the payload
        // that clears the stored key, and it is falsy. The contract is that the
        // property is absent, which is the only value that means "leave it".
        expect(patchBody, 'an unrelated edit sends no api_key').not.toHaveProperty('api_key');
        expect(patchBody, 'a static-key provider sends no auth_config').not.toHaveProperty(
          'auth_config',
        );
      });

      await test.step('The stored key is byte-identical and the URL is the new one', async () => {
        const stored = await backendClient.getProviderKey(provider.id);
        expect(stored.apiKey, 'the masked key after the edit').toBe(maskedKeyBefore);
        expect(stored.baseUrl, 'the base_url after the edit').toBe(EDITED_URL);
        expect(stored.authConfig, 'the edit introduced no auth_config').toBeNull();
      });
    },
  );
});
