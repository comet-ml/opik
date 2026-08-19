import { test, expect } from '@e2e/fixtures';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import {
  MOCK_AUTH_CLIENT_ID,
  MOCK_AUTH_CLIENT_SECRET,
  mockGatewayUrlForBackend,
  mockTokenUrlForBackend,
} from '@e2e/core/mock-auth';
import {
  checkProviderAuthConfig,
  deleteProviderKeyByName,
  createProviderKey,
  findProviderKeyByName,
} from '@e2e/core/provider-keys';

const SECRET_SENTINEL = '__SECRET__';

/**
 * Dialog contracts of the OAuth2 client-credentials auth mode (OPIK-7940),
 * against the suite's hermetic mock token service — no external keys involved.
 * Provider keys are workspace-global: every test uses a namespaced provider_name
 * and deletes it in a finally block.
 */
test.describe('AI Providers — OAuth2 token auth', { tag: ['@t1-smoke', '@area:ai-providers'] }, () => {
  test('configure with test connection, persist the recipe masked', { tag: ['@cap:configuration.ai-provider-add'] }, async ({
    page,
    testNamespace,
  }) => {
    const providerName = `${testNamespace}-oauth-create`;
    const configuration = new ConfigurationPage(page);

    try {
      const dialog = await test.step('Open the add-provider dialog (Custom)', async () => {
        await configuration.gotoAiProviders();
        return configuration.openAddCustomProviderDialog();
      });
      test.skip(dialog === null, 'Custom provider option not offered by this deployment');
      if (dialog === null) return;

      await test.step('Fill the provider with OAuth2 client credentials', async () => {
        await dialog.getByRole('textbox', { name: 'Provider name' }).fill(providerName);
        await dialog.getByRole('textbox', { name: 'URL', exact: true }).fill(mockGatewayUrlForBackend);
        await dialog.getByRole('textbox', { name: 'Models list' }).fill('mock-model');
        await configuration.fillAuthSection(dialog, {
          tokenUrl: mockTokenUrlForBackend,
          clientId: MOCK_AUTH_CLIENT_ID,
          clientSecret: MOCK_AUTH_CLIENT_SECRET,
        });
      });

      await test.step('Test connection succeeds and reports the token lifetime', async () => {
        await configuration.clickTestConnection(dialog);
        await expect(page.getByText('Connection successful', { exact: true })).toBeVisible();
        await expect(page.getByText(/valid for 3600 seconds/).first()).toBeVisible();
      });

      await test.step('Save and verify the provider row appears', async () => {
        await dialog.getByRole('button', { name: 'Add provider', exact: true }).click();
        await dialog.waitFor({ state: 'hidden' });
        await expect
          .poll(async () => configuration.hasCustomProvider(providerName), {
            timeout: 15_000,
            intervals: [500, 1000],
          })
          .toBe(true);
      });

      await test.step('Backend stores the full recipe with the secret masked', async () => {
        const stored = await findProviderKeyByName(providerName);
        expect(stored).not.toBeNull();
        expect(stored!.auth_config?.token_url).toBe(mockTokenUrlForBackend);
        expect(stored!.auth_config?.send_as).toBe('basic');

        const byKey = Object.fromEntries(
          (stored!.auth_config?.credentials ?? []).map((c) => [c.key, c]),
        );
        // grant_type is injected by the FE (hidden from the dialog) per the OAuth2 spec
        expect(byKey.grant_type).toMatchObject({ value: 'client_credentials', secret: false });
        expect(byKey.client_id).toMatchObject({ value: MOCK_AUTH_CLIENT_ID, secret: false });
        // the secret is write-only: read-back must return the sentinel, never the value
        expect(byKey.client_secret).toMatchObject({ value: SECRET_SENTINEL, secret: true });
      });
    } finally {
      await deleteProviderKeyByName(providerName);
    }
  });

  test('sentinel round-trip: reopen shows a write-only secret and updating keeps it', { tag: ['@cap:configuration.ai-provider-edit'] }, async ({
    page,
    testNamespace,
  }) => {
    const providerName = `${testNamespace}-oauth-edit`;
    const configuration = new ConfigurationPage(page);

    try {
      await test.step('Seed an OAuth2 provider via REST', async () => {
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: mockGatewayUrlForBackend,
          configuration: { models: `custom-llm/${providerName}/mock-model` },
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
      });

      const dialog = await test.step('Open the edit dialog', async () => {
        await configuration.gotoAiProviders();
        return configuration.openCustomProviderEdit(providerName);
      });

      await test.step('OAuth2 mode is preselected with stored values; the secret is write-only', async () => {
        await expect(dialog.getByRole('radio', { name: 'OAuth2 client credentials' })).toBeChecked();
        await expect(dialog.getByRole('textbox', { name: 'Token URL' })).toHaveValue(
          mockTokenUrlForBackend,
        );

        // grant_type stays hidden — only user-owned rows are listed
        expect(await configuration.credentialKeys(dialog)).not.toContain('grant_type');
        const idRow = await configuration.credentialRow(dialog, 'client_id');
        await expect(idRow.getByTestId('auth-credential-value')).toHaveValue(MOCK_AUTH_CLIENT_ID);

        const secretRow = await configuration.credentialRow(dialog, 'client_secret');
        await expect(secretRow.getByTestId('auth-credential-value')).toHaveValue(SECRET_SENTINEL);
        // stored secrets stay masked: no reveal (eye) affordance, and the lock can't be removed.
        // Row buttons are [eye?, lock, remove] — exactly 2 means the eye is absent.
        await expect(secretRow.getByRole('button')).toHaveCount(2);
        await expect(secretRow.getByTestId('auth-credential-lock')).toBeDisabled();
      });

      await test.step('Test connection resolves the stored secret server-side', async () => {
        await configuration.clickTestConnection(dialog);
        await expect(page.getByText('Connection successful', { exact: true })).toBeVisible();
      });

      await test.step('Update without changes; the stored secret survives the sentinel round-trip', async () => {
        await dialog.getByRole('button', { name: 'Update configuration', exact: true }).click();
        await dialog.waitFor({ state: 'hidden' });

        const stored = await findProviderKeyByName(providerName);
        expect(stored).not.toBeNull();
        // still masked on read-back, and still usable: the server-side check
        // fetches a token with the stored (decrypted) secret
        const secret = stored!.auth_config?.credentials?.find((c) => c.key === 'client_secret');
        expect(secret?.value).toBe(SECRET_SENTINEL);
        const check = await checkProviderAuthConfig(stored!.id);
        expect(check.lifetime_seconds).toBe(3600);
      });
    } finally {
      await deleteProviderKeyByName(providerName);
    }
  });

  test('switching back to a static API key clears the stored recipe', { tag: ['@cap:configuration.ai-provider-edit'] }, async ({
    page,
    testNamespace,
  }) => {
    const providerName = `${testNamespace}-oauth-clear`;
    const configuration = new ConfigurationPage(page);

    try {
      await test.step('Seed an OAuth2 provider via REST', async () => {
        await createProviderKey({
          provider: 'custom-llm',
          provider_name: providerName,
          base_url: mockGatewayUrlForBackend,
          configuration: { models: `custom-llm/${providerName}/mock-model` },
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
      });

      await test.step('Switch the auth mode to Static API key and save', async () => {
        await configuration.gotoAiProviders();
        const dialog = await configuration.openCustomProviderEdit(providerName);
        await dialog.getByRole('radio', { name: 'Static API key' }).click();
        await dialog.getByRole('textbox', { name: 'API key' }).fill('mock-static-key');
        await dialog.getByRole('button', { name: 'Update configuration', exact: true }).click();
        await dialog.waitFor({ state: 'hidden' });
      });

      await test.step('The stored recipe is cleared', async () => {
        const stored = await findProviderKeyByName(providerName);
        expect(stored).not.toBeNull();
        expect(stored!.auth_config).toBeFalsy();
      });
    } finally {
      await deleteProviderKeyByName(providerName);
    }
  });

  test('failed test connection surfaces a redacted error', { tag: ['@cap:configuration.ai-provider-add'] }, async ({ page, testNamespace }) => {
    const configuration = new ConfigurationPage(page);
    const wrongSecret = `wrong-secret-${testNamespace}`;

    await configuration.gotoAiProviders();
    const dialog = await configuration.openAddCustomProviderDialog();
    test.skip(dialog === null, 'Custom provider option not offered by this deployment');
    if (dialog === null) return;

    await test.step('Fill auth with a wrong client secret and test the connection', async () => {
      await configuration.fillAuthSection(dialog, {
        tokenUrl: mockTokenUrlForBackend,
        clientId: MOCK_AUTH_CLIENT_ID,
        clientSecret: wrongSecret,
      });
      await configuration.clickTestConnection(dialog);
    });

    await test.step('The failure toast names the upstream 401 and never the secret', async () => {
      await expect(page.getByText('Connection failed', { exact: true })).toBeVisible();
      await expect(page.getByText(/401/).first()).toBeVisible();
      await expect(page.getByText(wrongSecret)).toHaveCount(0);
    });

    await page.keyboard.press('Escape');
  });
});
