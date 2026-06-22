import { Page } from '@playwright/test';
import { BasePage } from './base.page';

export class TestSuitesPage extends BasePage {
  constructor(page: Page, baseUrl: string, workspace: string) {
    super(page, baseUrl, workspace);
  }

  async goto(projectId: string): Promise<void> {
    await this.page.goto(this.url(`projects/${projectId}/test-suites`));
    await this.page.waitForLoadState('load');
    await this.dismissWelcomeDialogIfPresent();
  }

  async waitForReady(expectedCellText: string): Promise<void> {
    await this.page.getByRole('heading', { name: 'Test suites', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await Promise.race([
      this.page.locator('tbody tr td').filter({ hasText: expectedCellText }).first().waitFor({ state: 'visible', timeout: 10000 }),
      this.page.getByRole('heading', { name: /no test suites yet/i }).waitFor({ state: 'visible', timeout: 8000 }),
    ]);
  }

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: 'Test suites', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await this.page.getByRole('heading', { name: /no test suites yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }
}
