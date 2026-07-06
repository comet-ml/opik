import type { Page } from '@playwright/test';
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
    const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
    const firstRowCell = this.page.getByTestId('ai-provider-row-cell').first();
    const emptyState = tabpanel.getByText('No AI providers yet');
    await Promise.race([
      firstRowCell.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => undefined),
      emptyState.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => undefined),
    ]);
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

  /**
   * Idempotency check for Custom providers — a Custom row is identified by
   * (data-provider=custom-llm, name cell text === providerName). The first
   * cell in each row contains the provider_name.
   */
  private async hasCustomProvider(providerName: string): Promise<boolean> {
    const tabpanel = this.page.getByTestId('ai-providers-tabpanel');
    const firstRowCell = this.page.getByTestId('ai-provider-row-cell').first();
    const emptyState = tabpanel.getByText('No AI providers yet');
    await Promise.race([
      firstRowCell.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => undefined),
      emptyState.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => undefined),
    ]);
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
