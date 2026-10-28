import type { Locator, Page } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * Provider name as shown in the FE. Must match `ProviderGridOption.label`
 * (the human-readable name on the per-provider button) AND the URL-friendly
 * `providerType` enum used in the `data-provider` attribute. Test data uses
 * the providerType (lowercased) for `data-provider` attribute matching.
 */
export type ProviderName =
  | 'OpenAI'
  | 'Anthropic'
  | 'OpenRouter'
  | 'Gemini'
  | 'Vertex AI'
  | 'Bedrock'
  | 'Ollama';

const PROVIDER_TYPE_MAP: Record<ProviderName, string> = {
  OpenAI: 'openai',
  Anthropic: 'anthropic',
  OpenRouter: 'openrouter',
  Gemini: 'gemini',
  'Vertex AI': 'vertex-ai',
  Bedrock: 'anthropic-vertex',
  Ollama: 'ollama',
};

/** data-provider value for the Custom (vLLM / OpenAI-compatible) option. */
const CUSTOM_PROVIDER_TYPE = 'custom-llm';

/**
 * OAuth2 client-credentials auth for a Custom provider (OPIK-7940). When present,
 * the provider is configured in "OAuth2 client credentials" mode instead of a
 * static API key: Opik fetches a short-lived bearer from `tokenUrl` before LLM calls.
 */
export interface CustomProviderAuthConfig {
  tokenUrl: string;
  clientId: string;
  clientSecret: string;
  /** Optional extra `scope` credential row (e.g. "ttl:10" against the mock service). */
  scope?: string;
}

export interface CustomProviderConfig {
  /** Unique provider_name (e.g. "openrouter"). Used to dedupe in the providers table. */
  providerName: string;
  /** Base URL of the OpenAI-compatible endpoint (e.g. "https://openrouter.ai/api/v1"). */
  baseUrl: string;
  /** Static API key. Ignored when `auth` is set (the field is hidden in OAuth2 mode). */
  apiKey?: string;
  /** Comma-separated model ids the gateway exposes (e.g. "openai/gpt-4o-mini"). */
  models: string;
  /** OAuth2 client credentials mode instead of a static API key. */
  auth?: CustomProviderAuthConfig;
}

/**
 * Workspace Configuration → AI Providers tab. Used by provider-sanity tests
 * for UI self-provisioning of provider keys: read the key from env, navigate
 * here, add it if absent. Idempotent.
 */
export class ConfigurationPage {
  constructor(private readonly page: Page) {}

  async gotoAiProviders(workspaceName?: string): Promise<void> {
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${workspaceName ?? env.workspace}/configuration?tab=ai-provider`);
    await this.page
      .getByRole('tab', { name: 'AI Providers', selected: true })
      .waitFor({ state: 'visible' });
    await this.page.getByTestId('ai-providers-tabpanel').waitFor({ state: 'visible' });
  }

  /**
   * Read the configured providers by inspecting `data-provider` attributes on
   * row cells. The tabpanel arrives visible before its row list resolves; if
   * we count rows before either a real row OR the empty-state marker has
   * rendered, we'd report "no providers" for a populated table and the
   * caller's idempotency check would fail. Wait for the table to settle first.
   */
  async listConfiguredProviders(): Promise<string[]> {
    await this.waitForProvidersTableSettled();
    const cells = this.page.getByTestId('ai-provider-row-cell');
    const count = await cells.count();
    const providers: string[] = [];
    for (let i = 0; i < count; i++) {
      const value = await cells.nth(i).getAttribute('data-provider');
      if (value) providers.push(value);
    }
    return providers;
  }

  async hasProvider(provider: ProviderName): Promise<boolean> {
    const expected = PROVIDER_TYPE_MAP[provider];
    const configured = await this.listConfiguredProviders();
    return configured.includes(expected);
  }

  /**
   * Add a provider's API key via the UI. Idempotent: if the provider is already
   * in the table, no-ops.
   *
   * Returns `false` when the deployment doesn't offer this provider in the
   * add-provider dialog — restricted environments expose only a subset of
   * providers, and a missing option must fall through to the next candidate
   * instead of hanging on a click that will never resolve. Returns `true` when
   * the provider is configured (either already present or added just now).
   */
  async ensureProviderConfigured(provider: ProviderName, apiKey: string): Promise<boolean> {
    return test.step(`ensure provider "${provider}" is configured`, async () => {
      if (await this.hasProvider(provider)) return true;

      // Two buttons can have the name "Add configuration": the toolbar button
      // (always visible) and an empty-state CTA inside the table's no-data row.
      // Scope to the toolbar button — it's always present.
      const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
      const toolbarButton = tabpanel
        .getByRole('button', { name: 'Add configuration', exact: true })
        .first();
      await toolbarButton.click();
      const dialog = this.page.getByTestId('add-provider-dialog');
      await dialog.waitFor({ state: 'visible' });

      // The provider option this candidate needs may be absent: a deployment
      // exposes only the providers its feature toggles enable, and the grid
      // renders nothing for the rest. Probe with a bounded wait so an absent
      // option (or an entirely empty grid) falls through instead of hanging on
      // the default 30s timeout.
      const providerType = PROVIDER_TYPE_MAP[provider];
      const providerButton = dialog
        .getByTestId('add-provider-dialog-option')
        .and(this.page.locator(`[data-provider="${providerType}"]`))
        .first();

      const offered = await providerButton
        .waitFor({ state: 'visible', timeout: 2_000 })
        .then(() => true)
        .catch(() => false);
      if (!offered) {
        await this.page.keyboard.press('Escape');
        await dialog.waitFor({ state: 'hidden' });
        return false;
      }
      await providerButton.click();

      // Step 2: API key input. The textbox accessible name follows
      // "<Provider> API Key" exactly (verified during Phase 3 discovery).
      await dialog.getByRole('textbox', { name: `${provider} API Key` }).fill(apiKey);
      await dialog.getByRole('button', { name: 'Add provider', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });

      // Wait for the new row to land in the table.
      await expect
        .poll(async () => this.hasProvider(provider), {
          timeout: 15_000,
          intervals: [500, 1000],
        })
        .toBe(true);
      return true;
    });
  }

  /**
   * Add a Custom (vLLM / OpenAI-compatible) provider via the UI. Distinct from
   * `ensureProviderConfigured` because Custom providers carry a user-defined
   * provider_name and require URL + Models list fields. Idempotent: if a row
   * matching the same provider_name is already present, no-ops.
   *
   * Returns `false` when the deployment doesn't offer the Custom option in the
   * dialog (its feature toggle is off), mirroring `ensureProviderConfigured` so
   * the caller can fall through instead of hanging on a missing option. Returns
   * `true` when the provider is configured (already present or added just now).
   */
  async ensureCustomProviderConfigured(config: CustomProviderConfig): Promise<boolean> {
    return test.step(`ensure custom provider "${config.providerName}" is configured`, async () => {
      if (await this.hasCustomProvider(config.providerName)) return true;

      const dialog = await this.openAddCustomProviderDialog();
      if (dialog === null) return false;

      await dialog.getByRole('textbox', { name: 'Provider name' }).fill(config.providerName);
      await dialog.getByRole('textbox', { name: 'URL', exact: true }).fill(config.baseUrl);
      await dialog.getByRole('textbox', { name: 'Models list' }).fill(config.models);
      if (config.auth) {
        await this.fillAuthSection(dialog, config.auth);
      } else {
        await dialog.getByRole('textbox', { name: 'API key' }).fill(config.apiKey ?? '');
      }

      await dialog.getByRole('button', { name: 'Add provider', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });

      await expect
        .poll(async () => this.hasCustomProvider(config.providerName), {
          timeout: 15_000,
          intervals: [500, 1000],
        })
        .toBe(true);
      return true;
    });
  }

  /**
   * Block until the providers table has resolved into either a populated or an
   * empty state. Counting rows before this settles reports zero for a populated
   * table, which makes callers re-add a provider that already exists — and that
   * redundant write invalidates the provider-key query right as dependent pages
   * (Playground, Online evaluation) are opening their model pickers.
   */
  /**
   * Open the add-provider dialog with the Custom option selected. Returns the
   * dialog locator, or null when the deployment doesn't offer the Custom option
   * (its feature toggle is off) — mirroring `ensureProviderConfigured`.
   */
  async openAddCustomProviderDialog(): Promise<Locator | null> {
    return test.step('open the add-provider dialog (Custom)', async () => {
      const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
      await tabpanel
        .getByRole('button', { name: 'Add configuration', exact: true })
        .first()
        .click();
      const dialog = this.page.getByTestId('add-provider-dialog');
      await dialog.waitFor({ state: 'visible' });

      // Each already-configured custom instance gets its own tile (opens EDIT for
      // that instance, marked with a "Configured" tag) — pick the generic add-new tile.
      const providerButton = dialog
        .getByTestId('add-provider-dialog-option')
        .and(this.page.locator(`[data-provider="${CUSTOM_PROVIDER_TYPE}"]`))
        .filter({ hasNot: this.page.getByText('Configured', { exact: true }) })
        .first();

      const offered = await providerButton
        .waitFor({ state: 'visible', timeout: 2_000 })
        .then(() => true)
        .catch(() => false);
      if (!offered) {
        await this.page.keyboard.press('Escape');
        await dialog.waitFor({ state: 'hidden' });
        return null;
      }
      await providerButton.click();
      return dialog;
    });
  }

  /**
   * Fill the Authentication card in "OAuth2 client credentials" mode. Selecting
   * the mode seeds two credential rows (client_id, client_secret); their value
   * inputs are filled by matching each row's key input, so row order never matters.
   */
  async fillAuthSection(dialog: Locator, auth: CustomProviderAuthConfig): Promise<void> {
    return test.step('fill the OAuth2 client credentials auth section', async () => {
      await dialog.getByRole('radio', { name: 'OAuth2 client credentials' }).click();
      await dialog.getByRole('textbox', { name: 'Token URL' }).fill(auth.tokenUrl);
      await this.fillCredentialValue(dialog, 'client_id', auth.clientId);
      await this.fillCredentialValue(dialog, 'client_secret', auth.clientSecret);
      if (auth.scope !== undefined) {
        await dialog.getByRole('button', { name: 'Add credential' }).click();
        const newRow = dialog.getByTestId('auth-credential-row').last();
        await newRow.getByTestId('auth-credential-key').fill('scope');
        await newRow.getByTestId('auth-credential-value').fill(auth.scope);
      }
    });
  }

  /**
   * The credential row whose key input currently holds `key`. Scans by input
   * value (a DOM property — controlled React inputs never update the `value`
   * attribute, so a CSS attribute filter would not match). Throws when absent.
   */
  async credentialRow(dialog: Locator, key: string): Promise<Locator> {
    const rows = dialog.getByTestId('auth-credential-row');
    await rows.first().waitFor({ state: 'visible' });
    const count = await rows.count();
    for (let i = 0; i < count; i++) {
      const row = rows.nth(i);
      if ((await row.getByTestId('auth-credential-key').inputValue()) === key) {
        return row;
      }
    }
    throw new Error(`no credential row with key "${key}"`);
  }

  /** The keys of every credential row currently in the dialog, in display order. */
  async credentialKeys(dialog: Locator): Promise<string[]> {
    const keys = dialog.getByTestId('auth-credential-key');
    const count = await keys.count();
    const values: string[] = [];
    for (let i = 0; i < count; i++) {
      values.push(await keys.nth(i).inputValue());
    }
    return values;
  }

  private async fillCredentialValue(dialog: Locator, key: string, value: string): Promise<void> {
    const row = await this.credentialRow(dialog, key);
    await row.getByTestId('auth-credential-value').fill(value);
  }

  /** Click "Test connection" in the auth section; assertions on the toast belong to the caller. */
  async clickTestConnection(dialog: Locator): Promise<void> {
    return test.step('click Test connection', async () => {
      await dialog.getByRole('button', { name: 'Test connection' }).click();
    });
  }

  // ── the dialog's custom-headers editor ──────────────────────────────────
  //
  // `CustomHeadersField` wraps the shared `KeyValueFieldArray`, which carries no
  // data-testid and whose inputs have no label or accessible name — only a
  // placeholder. Placeholder is therefore the most stable handle available here,
  // and it is unambiguous: the same component is mounted a second time in this
  // dialog for query parameters, but with "Parameter name"/"Parameter value",
  // and a third time for auth credentials, which DO carry testids. A
  // `data-testid` on the shared component would be better and belongs in a
  // frontend change; these specs run against a deployed image, so they cannot
  // add one and have it render.

  /** Every header-name input in the dialog, in row order. */
  headerKeyInputs(dialog: Locator): Locator {
    return dialog.getByPlaceholder('Header name');
  }

  /** Every header-value input in the dialog, in row order. */
  headerValueInputs(dialog: Locator): Locator {
    return dialog.getByPlaceholder('Header value');
  }

  /**
   * Append header rows and fill them, as a user would: one "Add header" click
   * per row, then the row's two inputs.
   *
   * Rows are addressed by INDEX here, which is the one place in this POM where
   * position is the identity: the editor is an ordered `useFieldArray` whose
   * rows have no name until this types one in, so there is nothing else to
   * match on. The count is asserted after each append so a build that stopped
   * adding rows fails here rather than silently filling the same row twice.
   *
   * A row's `key` or `value` may be the empty string — the blank-name and
   * blank-row cases are exactly what the validation rules are about.
   */
  async addHeaderRows(
    dialog: Locator,
    rows: ReadonlyArray<{ key: string; value: string }>,
  ): Promise<void> {
    return test.step(`add ${rows.length} custom header row(s)`, async () => {
      const addButton = dialog.getByRole('button', { name: 'Add header', exact: true });
      const before = await this.headerKeyInputs(dialog).count();
      for (let i = 0; i < rows.length; i++) {
        await addButton.click();
        await expect(
          this.headerKeyInputs(dialog),
          'the "Add header" click appended exactly one row',
        ).toHaveCount(before + i + 1);
        const row = before + i;
        if (rows[i].key !== '') await this.headerKeyInputs(dialog).nth(row).fill(rows[i].key);
        if (rows[i].value !== '') await this.headerValueInputs(dialog).nth(row).fill(rows[i].value);
      }
    });
  }

  /** Clear the name of the header row at `index` — the blank-name case. */
  async clearHeaderKey(dialog: Locator, index: number): Promise<void> {
    return test.step(`clear the name of header row ${index}`, async () => {
      await this.headerKeyInputs(dialog).nth(index).fill('');
    });
  }

  /** Overwrite the name of the header row at `index`. */
  async setHeaderKey(dialog: Locator, index: number, key: string): Promise<void> {
    return test.step(`set header row ${index} name to "${key}"`, async () => {
      await this.headerKeyInputs(dialog).nth(index).fill(key);
    });
  }

  /**
   * The header rows the dialog currently holds, in display order, read from the
   * inputs' DOM values.
   *
   * `inputValue()` rather than an attribute filter: these are controlled React
   * inputs, which never update the `value` attribute, so CSS cannot see what is
   * typed. Same reason `credentialRow` scans by value.
   *
   * For a spec asserting that an EDIT dialog repopulated what the backend
   * stored — a rule that locks a user out of their own provider key is only
   * visible once the stored rows are back on screen.
   */
  async readHeaderRows(dialog: Locator): Promise<Array<{ key: string; value: string }>> {
    return test.step('read the header rows the dialog shows', async () => {
      const keys = this.headerKeyInputs(dialog);
      const values = this.headerValueInputs(dialog);
      const count = await keys.count();
      expect(
        await values.count(),
        'every header row has both a name and a value input',
      ).toBe(count);
      const rows: Array<{ key: string; value: string }> = [];
      for (let i = 0; i < count; i++) {
        rows.push({ key: await keys.nth(i).inputValue(), value: await values.nth(i).inputValue() });
      }
      return rows;
    });
  }

  /**
   * The field error the headers editor is showing for the row at `index`.
   *
   * Resolved through the form's own ARIA wiring rather than by walking the DOM:
   * `FormControl` stamps the input `id="<n>-form-item"` and points its
   * `aria-describedby` at `"<n>-form-item-message"`, which is the id
   * `FormMessage` renders under. Deriving the message element from the input's
   * own id means the lookup survives any re-layout of the row, and — unlike a
   * dialog-wide text match — it cannot be satisfied by the identical message
   * sitting under the query-parameter editor or under another header row, which
   * is precisely the confusion a uniqueness rule's test must avoid.
   *
   * Throws when the input carries no id, rather than returning a locator that
   * can never match: that would turn a frontend change into a silent pass.
   */
  async headerKeyError(dialog: Locator, index: number): Promise<Locator> {
    const input = this.headerKeyInputs(dialog).nth(index);
    await expect(input, `header row ${index} is on screen`).toBeVisible();
    const inputId = await input.getAttribute('id');
    if (!inputId?.endsWith('-form-item')) {
      throw new Error(
        `header row ${index}'s name input has id "${inputId}", not the ` +
          '"<n>-form-item" shape FormControl stamps — its error message cannot be located',
      );
    }
    // `[id="..."]`, not `#...`: React's `useId` mints ids of the form `:r6a:`,
    // whose colons are not valid in a CSS id selector — Playwright rejects
    // `#:r6a:-form-item-message` outright rather than failing to match.
    return dialog.locator(`[id="${inputId}-message"]`);
  }

  /**
   * Submit the add-provider dialog. Deliberately does NOT wait for it to close:
   * every rule under test here is one that must keep the dialog OPEN, so the
   * caller decides which outcome to assert.
   */
  async submitAddProvider(dialog: Locator): Promise<void> {
    return test.step('submit the add-provider dialog', async () => {
      await dialog.getByRole('button', { name: 'Add provider', exact: true }).click();
    });
  }

  /** As {@link submitAddProvider}, for the edit dialog's own button. */
  async submitEditProvider(dialog: Locator): Promise<void> {
    return test.step('submit the edit-provider dialog', async () => {
      await dialog.getByRole('button', { name: 'Update configuration', exact: true }).click();
    });
  }

  /** Open the edit dialog for a Custom provider row and return the dialog locator. */
  async openCustomProviderEdit(providerName: string): Promise<Locator> {
    return test.step(`open edit dialog for custom provider "${providerName}"`, async () => {
      const row = await this.customProviderRow(providerName);
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const dialog = this.page.getByRole('dialog', { name: 'Edit provider configuration' });
      await dialog.waitFor({ state: 'visible' });
      return dialog;
    });
  }

  /** Delete a Custom provider row through its actions menu. Used for spec teardown. */
  async deleteCustomProvider(providerName: string): Promise<void> {
    return test.step(`delete custom provider "${providerName}"`, async () => {
      const row = await this.customProviderRow(providerName);
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();
      await this.page
        .getByRole('button', { name: 'Delete configuration', exact: true })
        .click();
      await expect
        .poll(async () => this.hasCustomProvider(providerName), {
          timeout: 15_000,
          intervals: [500, 1000],
        })
        .toBe(false);
    });
  }

  private async customProviderRow(providerName: string): Promise<Locator> {
    await this.waitForProvidersTableSettled();
    const row = this.page
      .locator('tr')
      .filter({
        has: this.page
          .getByTestId('ai-provider-row-cell')
          .and(this.page.locator(`[data-provider="${CUSTOM_PROVIDER_TYPE}"]`)),
      })
      .filter({ has: this.page.getByText(providerName, { exact: true }) })
      .first();
    await row.waitFor({ state: 'visible' });
    return row;
  }

  private async waitForProvidersTableSettled(): Promise<void> {
    const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
    const firstRowCell = this.page.getByTestId('ai-provider-row-cell').first();
    const emptyState = tabpanel.getByText('No AI providers yet');
    await expect
      .poll(
        async () =>
          (await firstRowCell.isVisible().catch(() => false)) ||
          (await emptyState.isVisible().catch(() => false)),
        { timeout: 30_000, intervals: [100, 250, 500] },
      )
      .toBe(true);
  }

  /**
   * Idempotency check for Custom providers — a Custom row is identified by
   * (data-provider=custom-llm, name cell text === providerName). The first
   * cell in each row contains the provider_name.
   */
  async hasCustomProvider(providerName: string): Promise<boolean> {
    await this.waitForProvidersTableSettled();
    const customCells = this.page
      .getByTestId('ai-provider-row-cell')
      .and(this.page.locator(`[data-provider="${CUSTOM_PROVIDER_TYPE}"]`));
    const count = await customCells.count();
    for (let i = 0; i < count; i++) {
      const row = customCells.nth(i).locator('xpath=ancestor::tr');
      if ((await row.getByText(providerName, { exact: true }).count()) > 0) {
        return true;
      }
    }
    return false;
  }
}
