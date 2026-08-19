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
 * Label of the *unconfigured* Custom option in the add-provider grid. The grid
 * also lists every already-configured custom provider under the same
 * `data-provider`, and picking one of those opens it for editing instead of
 * starting a new configuration — so a fresh add has to name this option.
 */
const CUSTOM_PROVIDER_OPTION_LABEL = 'vLLM / Custom provider';

export type AuthMode = 'Static API key' | 'OAuth2 client credentials';

/** One credential row of the Authentication card, as the dialog renders it. */
export interface CredentialRowState {
  key: string;
  value: string;
  placeholder: string;
  /** Lock engaged: the value is marked secret. */
  locked: boolean;
  /** Lock toggle disabled — a saved secret's lock can never be removed. */
  lockToggleDisabled: boolean;
  /** A reveal (eye) button is offered. Never for a value that is only stored. */
  revealable: boolean;
  /** The value is rendered obscured rather than in clear. */
  masked: boolean;
}

export interface SubmittedRequest {
  status: number;
  payload: Record<string, unknown>;
}

export interface CustomProviderConfig {
  /** Unique provider_name (e.g. "openrouter"). Used to dedupe in the providers table. */
  providerName: string;
  /** Base URL of the OpenAI-compatible endpoint (e.g. "https://openrouter.ai/api/v1"). */
  baseUrl: string;
  apiKey: string;
  /** Comma-separated model ids the gateway exposes (e.g. "openai/gpt-4o-mini"). */
  models: string;
}

/**
 * Workspace Configuration → AI Providers tab. Used by provider-sanity tests
 * for UI self-provisioning of provider keys: read the key from env, navigate
 * here, add it if absent. Idempotent.
 */
export class ConfigurationPage {
  constructor(private readonly page: Page) {}

  async gotoAiProviders(): Promise<void> {
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${env.workspace}/configuration?tab=ai-provider`);
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

      const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
      const toolbarButton = tabpanel
        .getByRole('button', { name: 'Add configuration', exact: true })
        .first();
      await toolbarButton.click();
      const dialog = this.page.getByTestId('add-provider-dialog');
      await dialog.waitFor({ state: 'visible' });

      const providerButton = dialog
        .getByTestId('add-provider-dialog-option')
        .and(this.page.locator(`[data-provider="${CUSTOM_PROVIDER_TYPE}"]`))
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

      await dialog.getByRole('textbox', { name: 'Provider name' }).fill(config.providerName);
      await dialog.getByRole('textbox', { name: 'URL' }).fill(config.baseUrl);
      await dialog.getByRole('textbox', { name: 'API key' }).fill(config.apiKey);
      await dialog.getByRole('textbox', { name: 'Models list' }).fill(config.models);

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

  // --- Custom provider dialog: Authentication card (dynamic token auth) ---

  /**
   * Narrow the providers table to a single provider and wait for the filter to
   * settle. The search is debounced, and acting on the table before it settles
   * addresses whatever row still occupies that position in the unfiltered list
   * — a row far enough down the page that its actions menu opens off-screen.
   */
  async searchForProvider(providerName: string): Promise<void> {
    return test.step(`search providers for "${providerName}"`, async () => {
      await this.page
        .getByTestId('ai-providers-tabpanel')
        .getByPlaceholder('Search by name')
        .fill(providerName);
      await expect(
        this.providerNameCells(),
        'the table narrowed to the searched provider',
      ).toHaveText([providerName]);
    });
  }

  /**
   * The table row for a custom provider, addressed by the name cell's exact
   * text. Anchored so `-uitoken` can't also match `-uitoken-2`, and the row is
   * not positional: this table sets no `getRowId`, so `data-row-id` carries the
   * row index rather than the provider id.
   */
  providerRow(providerName: string): Locator {
    return this.page.getByRole('row').filter({
      has: this.page
        .getByRole('cell')
        .filter({ hasText: new RegExp(`^\\s*${escapeForRegExp(providerName)}\\s*$`) }),
    });
  }

  /** Open a blank Custom provider configuration. */
  async openAddCustomProviderDialog(): Promise<void> {
    return test.step('open the add Custom provider dialog', async () => {
      const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
      await tabpanel.getByRole('button', { name: 'Add configuration', exact: true }).first().click();
      const dialog = this.providerDialog();
      await dialog.waitFor({ state: 'visible' });
      await dialog
        .getByTestId('add-provider-dialog-option')
        .filter({ hasText: CUSTOM_PROVIDER_OPTION_LABEL })
        .click();
      await expect(dialog.getByLabel('Provider name', { exact: true })).toBeVisible();
    });
  }

  /** Open an existing provider's configuration from its row's actions menu. */
  async openEditDialogFor(providerName: string): Promise<void> {
    return test.step(`open the edit dialog for "${providerName}"`, async () => {
      const row = this.providerRow(providerName);
      await expect(row, `exactly one row named "${providerName}"`).toHaveCount(1);
      const actions = row.getByRole('button', { name: 'Actions menu' });
      // The row re-renders as it takes hover, which swaps the trigger out from
      // under the first pointerdown and leaves the menu closed. Settling the
      // hover first makes the click land on the element that stays.
      await actions.hover();
      await actions.click();
      // Don't re-address the trigger from here on: the open menu is modal and
      // hides the rest of the page from the accessibility tree, so any
      // role-based locator outside it stops resolving until the menu closes.
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      const dialog = this.providerDialog();
      await dialog.waitFor({ state: 'visible' });
      await expect(dialog.getByLabel('Models list', { exact: true })).toBeVisible();
    });
  }

  async fillCustomProviderBasics(fields: {
    providerName?: string;
    baseUrl?: string;
    models?: string;
  }): Promise<void> {
    return test.step('fill the custom provider fields', async () => {
      const dialog = this.providerDialog();
      if (fields.providerName !== undefined) {
        await dialog.getByLabel('Provider name', { exact: true }).fill(fields.providerName);
      }
      if (fields.baseUrl !== undefined) {
        await dialog.getByLabel('URL', { exact: true }).fill(fields.baseUrl);
      }
      if (fields.models !== undefined) {
        await dialog.getByLabel('Models list', { exact: true }).fill(fields.models);
      }
    });
  }

  async selectAuthMode(mode: AuthMode): Promise<void> {
    return test.step(`select the "${mode}" authentication mode`, async () => {
      await this.providerDialog().getByRole('radio', { name: mode, exact: true }).click();
    });
  }

  async fillTokenUrl(url: string): Promise<void> {
    return test.step(`fill the token URL "${url}"`, async () => {
      await this.providerDialog().getByLabel('Token URL', { exact: true }).fill(url);
    });
  }

  /** Set the value of the credential row carrying `key`. */
  async fillCredentialValue(key: string, value: string): Promise<void> {
    return test.step(`fill credential "${key}"`, async () => {
      const row = await this.credentialRowFor(key);
      await this.valueInput(row).fill(value);
    });
  }

  async addCredential(credential: { key: string; value: string }): Promise<void> {
    return test.step(`add credential "${credential.key}"`, async () => {
      const dialog = this.providerDialog();
      await dialog.getByRole('button', { name: 'Add credential' }).click();
      const rows = this.credentialRows();
      const added = rows.nth((await rows.count()) - 1);
      await added.getByPlaceholder('Field name', { exact: true }).fill(credential.key);
      await this.valueInput(added).fill(credential.value);
    });
  }

  /**
   * Every credential row, in the order the dialog renders them. Returned as a
   * whole so a spec can assert the entire set rather than probing for the row
   * it expects — an extra or missing row is then a failure, not a silent pass.
   */
  async readCredentialRows(): Promise<CredentialRowState[]> {
    return test.step('read the credential rows', async () => {
      const rows = this.credentialRows();
      const count = await rows.count();
      const states: CredentialRowState[] = [];
      for (let i = 0; i < count; i++) {
        const row = rows.nth(i);
        const value = this.valueInput(row);
        const lockToggle = this.lockToggle(row);
        states.push({
          key: await row.getByPlaceholder('Field name', { exact: true }).inputValue(),
          value: await value.inputValue(),
          placeholder: (await value.getAttribute('placeholder')) ?? '',
          locked: (await lockToggle.locator('svg.lucide-lock').count()) > 0,
          lockToggleDisabled: await lockToggle.isDisabled(),
          revealable: (await this.revealToggle(row).count()) > 0,
          // EyeInput obscures through -webkit-text-security rather than
          // type=password, so this is what "not rendered in clear" means here.
          masked: await value.evaluate(
            (input) =>
              (input.style as CSSStyleDeclaration & { webkitTextSecurity?: string })
                .webkitTextSecurity === 'disc',
          ),
        });
      }
      return states;
    });
  }

  /**
   * Submit the dialog and return the write it actually sent. The dialog closes
   * on submit whether or not the request succeeded, so "it closed" proves
   * nothing — the request's status is the only signal available in the UI.
   */
  async submitProviderDialog(action: 'create' | 'update'): Promise<SubmittedRequest> {
    return test.step(`submit the provider dialog (${action})`, async () => {
      const method = action === 'create' ? 'POST' : 'PATCH';
      const dialog = this.providerDialog();
      const buttonName = action === 'create' ? 'Add provider' : 'Update configuration';

      const responsePromise = this.page.waitForResponse((response) => {
        // The collection route the FE posts to carries a trailing slash; the
        // item route it patches does not. Both, and neither the auth-config
        // test endpoint the same dialog can fire nor any other route.
        const path = new URL(response.url()).pathname.replace(/\/$/, '');
        return (
          response.request().method() === method &&
          /\/v1\/private\/llm-provider-key(\/[0-9a-f-]{36})?$/.test(path)
        );
      });
      await dialog.getByRole('button', { name: buttonName, exact: true }).click();
      const response = await responsePromise;
      await dialog.waitFor({ state: 'hidden' });

      return {
        status: response.status(),
        payload: (response.request().postDataJSON() ?? {}) as Record<string, unknown>,
      };
    });
  }

  private providerDialog(): Locator {
    return this.page.getByTestId('add-provider-dialog');
  }

  /** Name-column cells, addressed by column rather than by position. */
  private providerNameCells(): Locator {
    return this.page.locator('[data-cell-id$="_name"]');
  }

  /**
   * The credential rows carry no `data-testid`, so each is addressed as the row
   * container of its "Field name" input. A descriptive testid on the row would
   * be the stable contract (see conventions.md, "Selector preference") but
   * adding one means editing the feature's own frontend from a QA-proposed
   * spec; flagged for the FE owners instead.
   */
  private credentialRows(): Locator {
    return this.providerDialog()
      .getByPlaceholder('Field name', { exact: true })
      .locator('xpath=ancestor::div[contains(@class,"items-start")][1]');
  }

  /** The row whose field name is exactly `key`; fails if it isn't unique. */
  private async credentialRowFor(key: string): Promise<Locator> {
    const rows = this.credentialRows();
    const count = await rows.count();
    const matches: number[] = [];
    for (let i = 0; i < count; i++) {
      const rowKey = await rows.nth(i).getByPlaceholder('Field name', { exact: true }).inputValue();
      if (rowKey === key) matches.push(i);
    }
    expect(matches, `exactly one credential row named "${key}"`).toHaveLength(1);
    return rows.nth(matches[0]);
  }

  /** Plain input for a clear value, EyeInput's for a secret one. */
  private valueInput(row: Locator): Locator {
    return row.getByPlaceholder(new RegExp(`^(Value|stored, write-only)$`));
  }

  /** Addressed by its icon: the row's buttons carry no accessible name. */
  private lockToggle(row: Locator): Locator {
    return row.locator('button:has(svg.lucide-lock), button:has(svg.lucide-lock-open)');
  }

  private revealToggle(row: Locator): Locator {
    return row.locator('button:has(svg.lucide-eye), button:has(svg.lucide-eye-off)');
  }

  /**
   * Block until the providers table has resolved into either a populated or an
   * empty state. Counting rows before this settles reports zero for a populated
   * table, which makes callers re-add a provider that already exists — and that
   * redundant write invalidates the provider-key query right as dependent pages
   * (Playground, Online evaluation) are opening their model pickers.
   */
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
  private async hasCustomProvider(providerName: string): Promise<boolean> {
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

function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
