import { Page, expect } from '@playwright/test';
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
  // auto-expands, so an experiment in any other group stays hidden until its group
  // is expanded. Group rows render after the first table row, so expand on every
  // retry rather than once up front.
  async waitForExperiment(experimentName: string): Promise<void> {
    const experiment = this.page.getByText(experimentName).first();
    const collapsedGroups = this.page
      .locator('tr.comet-table-row-group')
      .locator('button:has(svg.lucide-chevron-right)');
    await expect(async () => {
      const collapsedCount = await collapsedGroups.count();
      for (let i = 0; i < collapsedCount; i++) {
        await collapsedGroups.first().click();
      }
      await expect(experiment).toBeVisible({ timeout: 1000 });
    }).toPass({ timeout: 15000 });
  }
}
