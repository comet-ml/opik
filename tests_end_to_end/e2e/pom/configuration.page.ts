import type { Page } from '@playwright/test';
import { expect } from '@playwright/test';
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

  /** Read the configured providers by inspecting `data-provider` attributes on row cells. */
  async listConfiguredProviders(): Promise<string[]> {
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
   */
  async ensureProviderConfigured(provider: ProviderName, apiKey: string): Promise<void> {
    if (await this.hasProvider(provider)) return;

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

    const providerType = PROVIDER_TYPE_MAP[provider];
    const providerButton = dialog
      .getByTestId('add-provider-dialog-option')
      .and(this.page.locator(`[data-provider="${providerType}"]`));
    await providerButton.first().click();

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
  }
}
