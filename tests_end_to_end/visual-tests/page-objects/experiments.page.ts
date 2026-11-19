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

  async waitForEmpty(): Promise<void> {
    await this.page.getByRole('heading', { name: 'Experiments', exact: true }).waitFor({ state: 'visible', timeout: 10000 });
    await this.page.getByRole('heading', { name: /no experiments yet/i }).waitFor({ state: 'visible', timeout: 20000 });
  }

  // Experiments are grouped by item source by default and only the first group
  // auto-expands, so an experiment in any other group is hidden until expanded.
  async expandCollapsedGroups(): Promise<void> {
    const collapsed = this.page
      .locator('tr.comet-table-row-group')
      .locator('button:has(svg.lucide-chevron-right)');
    for (let i = 0; i < 10 && (await collapsed.count()) > 0; i++) {
      await collapsed.first().click();
    }
  }

  async waitForExperiment(experimentName: string): Promise<void> {
    await this.page.getByText(experimentName).first().waitFor({ state: 'visible', timeout: 15000 });
  }
}
