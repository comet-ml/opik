import { Page } from '@playwright/test';
import { AIProvidersConfigPage } from '../page-objects/ai-providers-config.page';
import { ProviderConfig } from './model-config-loader';

export class AIProviderSetupHelper {
  private page: Page;
  private aiProvidersPage: AIProvidersConfigPage;

  constructor(page: Page) {
    this.page = page;
    this.aiProvidersPage = new AIProvidersConfigPage(page);
  }

  async setupProviderIfNeeded(providerName: string, providerConfig: ProviderConfig): Promise<void> {
    console.log(`Setting up AI provider for ${providerConfig.display_name}`);
    await this.aiProvidersPage.goto();

    if (await this.aiProvidersPage.checkProviderExists(providerConfig.api_key_env_var)) {
      console.log(`AI provider ${providerConfig.display_name} already exists, skipping setup`);
      return;
    }

    const apiKey = process.env[providerConfig.api_key_env_var];
    if (!apiKey) {
      throw new Error(
        `API key not found for ${providerConfig.display_name} (env var: ${providerConfig.api_key_env_var})`
      );
    }

    await this.aiProvidersPage.addProvider(providerName.toLowerCase() as 'OpenAI' | 'Anthropic', apiKey);

    console.log(`Successfully set up AI provider for ${providerConfig.display_name}`);
  }

  async cleanupProvider(providerConfig: ProviderConfig): Promise<void> {
    console.log(`Cleaning up AI provider configuration for ${providerConfig.display_name}`);
    await this.aiProvidersPage.goto();
    try {
      await this.aiProvidersPage.deleteProvider(providerConfig.api_key_env_var);
    } catch (error) {
      console.warn(`Failed to clean up provider configuration: ${error}`);
    }
  }
}
