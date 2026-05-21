import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class DatasetsPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/datasets`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForReady(expectedCellText: string): Promise<void> {
    await this.page.getByRole('heading', { name: 'Datasets', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await Promise.race([
      this.page.locator('tbody tr td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 10000 }),
      this.page.getByRole('heading', { name: /no datasets yet/i }).waitFor({ state: 'visible', timeout: 8000 }),
    ]);
  }
}
