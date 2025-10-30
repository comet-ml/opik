import { Page, expect, Locator } from '@playwright/test';

export class AIProvidersConfigPage {
  readonly page: Page;
  readonly addProviderButton: Locator;
  readonly searchBar: Locator;

  constructor(page: Page) {
    this.page = page;
    this.addProviderButton = page.getByRole('button', { name: 'Add configuration' }).first();
    this.searchBar = page.getByTestId('search-input');
  }

  async goto(): Promise<void> {
    await this.page.goto('/default/configuration?tab=ai-provider');
    await this.page.waitForLoadState('networkidle');
  }

  async searchProviderByName(providerName: string): Promise<void> {
    await this.searchBar.click();
    await this.searchBar.fill(providerName);
    await this.page.waitForTimeout(500);
  }

  async addProvider(providerType: 'OpenAI' | 'Anthropic', apiKey: string): Promise<void> {
    await this.addProviderButton.click();

    await this.page.getByRole('combobox').click();
    await this.page.getByRole('option', { name: providerType }).click();

    await this.page.getByLabel('API key').fill(apiKey);

    await this.page.getByRole('button', { name: 'Add configuration' }).click();
  }

  async editProvider(name: string, apiKey?: string): Promise<void> {
    await this.searchProviderByName(name);

    if (apiKey) {
      await this.page.getByLabel('API key').fill(apiKey);
    }

    await this.page.getByRole('button', { name: 'Update configuration' }).click();
  }

  async deleteProvider(providerName: string): Promise<void> {
    await this.searchProviderByName(providerName);

    await this.page.getByRole('row', { name: providerName }).getByRole('button', { name: 'Actions menu' }).click();
    await this.page.getByRole('menuitem', { name: 'Delete' }).click();

    await this.page.getByRole('button', { name: 'Delete configuration' }).click();
  }

  async checkProviderExists(providerName: string): Promise<boolean> {
    await this.searchProviderByName(providerName);
    try {
      const providerRow = this.page.getByRole('row').filter({ hasText: providerName });
      const exists = (await providerRow.count()) > 0;
      await this.searchBar.fill('');
      return exists;
    } catch (error) {
      await this.searchBar.fill('');
      return false;
    }
  }

  async checkProviderNotExists(providerName: string): Promise<void> {
    await this.searchProviderByName(providerName);
    await expect(this.page.getByText(providerName).first()).not.toBeVisible();
    await this.searchBar.fill('');
  }
}
