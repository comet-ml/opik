import type { Locator, Page } from '@playwright/test';
import { test, expect } from '@playwright/test';

/**
 * The update request the dialog PATCHes, in wire shape.
 *
 * Snake_case on purpose: this is the JSON body as `useProviderKeysUpdateMutation`
 * builds it, not a camelCase view of it, and the field names are half of what
 * the token-auth tests assert. `api_key: ''` (clear the static key) and
 * `auth_config: {}` (clear the recipe) are both meaningful values, so every
 * field stays optional rather than defaulted.
 */
export interface ProviderUpdatePayload {
  api_key?: string;
  auth_config?: {
    token_url?: string;
    send_as?: string;
    credentials?: Array<{ key: string; value: string; secret: boolean }>;
  };
}

/** The credentials a fresh OAuth2 recipe is filled with, in the order the form creates them. */
export interface Oauth2RecipeSeed {
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
}

/**
 * The Add / Edit provider configuration dialog (`ManageAIProviderDialog`),
 * reached from Configuration -> AI providers.
 *
 * Scoped to the dynamic-token-auth section (OPIK-7940): the Authentication
 * radio pair, the OAuth2 recipe fields, and the save round-trip. The dialog has
 * no `data-testid`s below its root, so every locator here is role-, label- or
 * placeholder-based; those are stable names the component sets deliberately
 * (`stored, write-only` is the placeholder the FE uses to say "a secret is
 * held here and cannot be read back"), not structural paths.
 */
export class AiProviderDialog {
  readonly root: Locator;

  constructor(
    private readonly page: Page,
    /** Used to match this provider's own PATCH; a stray sibling request must not satisfy the wait. */
    private readonly providerId: string,
  ) {
    this.root = page.getByTestId('add-provider-dialog');
  }

  async waitForReady(): Promise<void> {
    return test.step('wait for the provider dialog', async () => {
      await this.root.waitFor({ state: 'visible' });
      await expect(
        this.root.getByRole('heading', { name: 'Edit provider configuration' }),
      ).toBeVisible();
    });
  }

  get staticModeRadio(): Locator {
    return this.root.getByRole('radio', { name: 'Static API key' });
  }

  get tokenModeRadio(): Locator {
    return this.root.getByRole('radio', { name: 'OAuth2 client credentials' });
  }

  get tokenUrlInput(): Locator {
    return this.root.getByLabel('Token URL');
  }

  /** Rendered only in static mode — the dialog hides it entirely under token auth. */
  get apiKeyInput(): Locator {
    return this.root.getByRole('textbox', { name: 'API key' });
  }

  /**
   * The value box of a credential whose secret is already stored. The FE gives
   * it this placeholder only in that state, so its presence IS the assertion
   * that the dialog treats the secret as write-only.
   */
  get storedSecretInput(): Locator {
    return this.root.getByPlaceholder('stored, write-only');
  }

  get credentialKeyInputs(): Locator {
    return this.root.getByPlaceholder('Field name');
  }

  /**
   * Value boxes of credentials with no stored secret. Exact, so it does not
   * also match the `stored, write-only` box.
   */
  get credentialValueInputs(): Locator {
    return this.root.getByPlaceholder('Value', { exact: true });
  }

  /** Everything the dialog renders, for asserting a plaintext secret is nowhere in it. */
  async visibleText(): Promise<string> {
    return this.root.innerText();
  }

  async selectTokenMode(): Promise<void> {
    return test.step('switch the dialog to OAuth2 client credentials', async () => {
      await this.tokenModeRadio.click();
      await expect(this.tokenModeRadio).toBeChecked();
    });
  }

  async selectStaticMode(): Promise<void> {
    return test.step('switch the dialog to a static API key', async () => {
      await this.staticModeRadio.click();
      await expect(this.staticModeRadio).toBeChecked();
    });
  }

  async fillApiKey(value: string): Promise<void> {
    return test.step('fill the static API key', async () => {
      await this.apiKeyInput.fill(value);
    });
  }

  /**
   * Fill the credential rows the form creates when token mode is first
   * selected: `client_id` then `client_secret`.
   *
   * The rows are addressed by index, and the indices are *verified* before use
   * — the keys are asserted first, so a reordering (or a change to which rows
   * the form seeds) fails loudly here instead of quietly writing the client id
   * into the secret field and testing the wrong thing.
   */
  async fillOauth2Recipe(seed: Oauth2RecipeSeed): Promise<void> {
    return test.step('fill the OAuth2 token recipe', async () => {
      await this.tokenUrlInput.fill(seed.tokenUrl);

      await expect(
        this.credentialKeyInputs,
        'selecting token mode seeds exactly the client_id and client_secret rows',
      ).toHaveCount(2);
      await expect(this.credentialKeyInputs.nth(0)).toHaveValue('client_id');
      await expect(this.credentialKeyInputs.nth(1)).toHaveValue('client_secret');

      await expect(
        this.credentialValueInputs,
        'neither seeded credential holds a stored secret yet, so both are writable',
      ).toHaveCount(2);
      await this.credentialValueInputs.nth(0).fill(seed.clientId);
      await this.credentialValueInputs.nth(1).fill(seed.clientSecret);
    });
  }

  /**
   * Submit the dialog and return the update request it actually sent, with the
   * status the backend answered.
   *
   * The request body is the point: what a mode switch or an untouched re-save
   * *sends* is where the secret-preserving sentinel and the mutual-exclusion
   * clearing are decided, and a body that dropped either would still leave a
   * dialog that closed and a table that looked right.
   */
  async saveAndCaptureUpdate(): Promise<{ status: number; body: ProviderUpdatePayload }> {
    return test.step('save the dialog and capture the update request', async () => {
      const [response] = await Promise.all([
        this.page.waitForResponse(
          (res) =>
            res.request().method() === 'PATCH' &&
            res.url().includes(`/llm-provider-key/${this.providerId}`),
        ),
        this.root.getByRole('button', { name: 'Update configuration', exact: true }).click(),
      ]);
      await this.root.waitFor({ state: 'hidden' });
      return {
        status: response.status(),
        body: (response.request().postDataJSON() ?? {}) as ProviderUpdatePayload,
      };
    });
  }
}
