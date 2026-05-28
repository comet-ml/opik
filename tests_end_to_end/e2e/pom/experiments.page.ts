import { expect, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { ExperimentDetailPage } from './experiment-detail.page';

export class ExperimentsPage {
  private projectId: string | null = null;

  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments`);
  }

  async waitForReady(): Promise<void> {
    const heading = this.page.getByRole('heading', { name: 'Experiments', level: 1 });
    await heading.waitFor({ state: 'visible' });
    const realRow = this.rows.first();
    const emptyState = this.page.getByText('No experiments yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  async countExperiments(): Promise<number> {
    return this.rows.count();
  }

  rowById(experimentId: string): Locator {
    return this.page.locator(`tr[data-row-id="${experimentId}"]`);
  }

  async expectExperimentNameInList(experimentId: string, expectedName: string): Promise<void> {
    const cell = this.page.locator(`td[data-cell-id="${experimentId}_name"]`);
    await expect(cell, `experiment row ${experimentId} name cell`).toHaveText(expectedName);
  }

  async openExperimentById(experimentId: string): Promise<ExperimentDetailPage> {
    if (!this.projectId) {
      throw new Error('ExperimentsPage.openExperimentById: call goto(projectId) first');
    }
    const row = this.rowById(experimentId);
    await row.waitFor({ state: 'visible' });
    // The row is cursor-pointer but the dataset cell contains a link to the
    // dataset page. Click the experiment-name cell to navigate to detail.
    await this.page.locator(`td[data-cell-id="${experimentId}_name"]`).click();
    await this.page.waitForURL((url) => {
      return (
        url.pathname.includes(`/experiments/`) &&
        url.pathname.endsWith(`/compare`) &&
        url.search.includes(encodeURIComponent(experimentId))
      );
    });
    return new ExperimentDetailPage(this.page, experimentId);
  }

  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }
}
