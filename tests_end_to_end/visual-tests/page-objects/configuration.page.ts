import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export type ConfigurationTab = 'feedback-definitions' | 'environments' | 'ai-provider' | 'workspace-preferences';

export class ConfigurationPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(tab: ConfigurationTab): Promise<void> {
    await this.page.goto(this.url(`configuration?tab=${tab}`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForFeedbackDefinitionsReady(expectedCellText: string): Promise<void> {
    await this.page.getByRole('heading', { name: 'Feedback definitions', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await this.page.locator('td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 10000 });
  }

  async waitForEnvironmentsReady(expectedCellText: string): Promise<void> {
    await this.page.getByRole('heading', { name: 'Environments', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await this.page.locator('td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 10000 });
  }

  async waitForEnvironmentsEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: 'No environments yet', exact: true }).waitFor({ state: 'visible', timeout: 20000 });
  }

  async waitForAiProvidersReady(expectedCellText: string): Promise<void> {
    await this.page.getByTestId('ai-providers-tabpanel').waitFor({ state: 'visible', timeout: 10000 });
    await this.page.locator('[data-testid="ai-provider-row-cell"]').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 10000 });
  }

  async waitForAiProvidersEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: 'No AI providers yet', exact: true }).waitFor({ state: 'visible', timeout: 20000 });
  }

  async waitForWorkspacePreferencesReady(): Promise<void> {
    await this.page.locator('td').filter({ hasText: 'Thread online scoring rule cooldown period' }).waitFor({ state: 'visible', timeout: 10000 });
  }
}
