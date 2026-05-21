import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class ExperimentsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/experiments`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForReady(): Promise<void> {
    await this.page.getByRole('heading', { name: 'Experiments', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await Promise.race([
      this.page.locator('tbody tr').first().waitFor({ state: 'visible', timeout: 8000 }),
      this.page.getByRole('heading', { name: /no experiments yet/i }).waitFor({ state: 'visible', timeout: 8000 }),
    ]);
  }

  async waitForExperiment(experimentName: string): Promise<void> {
    await this.page.getByText(experimentName).first().waitFor({ state: 'visible', timeout: 15000 });
  }
}
