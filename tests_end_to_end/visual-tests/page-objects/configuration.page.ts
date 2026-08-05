import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export type ConfigurationTab = 'feedback-definitions' | 'environments' | 'ai-provider' | 'workspace-preferences';

export class ConfigurationPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  // Visiting a project first (rather than going straight to Configuration) pins the
  // sidebar's "Back to <project>" state to a known project — otherwise it falls back
  // to whichever project was most recently updated, an environment-dependent name that
  // shifts the sidebar's pixel width and fails the screenshot comparison.
  async goto(tab: ConfigurationTab, projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/home`));
    await this.page.waitForLoadState('load');
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
