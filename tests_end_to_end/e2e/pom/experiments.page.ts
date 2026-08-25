import { expect, test, type Locator, type Page } from '@playwright/test';
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

  /**
   * Deletes via the row's actions menu and confirms. The confirm dialog's
   * heading and its confirm button share the accessible name "Delete
   * experiment", so the dialog is scoped by heading first and the button is
   * then resolved by role within it (ConfirmDialog is a generic shared
   * component with no data-testid of its own).
   *
   * Both the row-actions and the bulk-selection paths issue the same
   * `POST /v1/private/experiments/delete` with an ids array — there is no
   * single-id DELETE endpoint for experiments.
   */
  async deleteExperimentById(experimentId: string): Promise<void> {
    return test.step(`delete experiment ${experimentId} via row actions`, async () => {
      const row = this.rowById(experimentId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();

      const confirm = this.deleteExperimentConfirmDialog;
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete experiment' }).click();

      await confirm.waitFor({ state: 'hidden' });
      // The list refetches rather than updating optimistically, and renders a
      // placeholder from cache while it does — so wait for the row itself to go.
      await row.waitFor({ state: 'detached' });
    });
  }

  /** The destructive confirm dialog raised by the row's Delete action. */
  get deleteExperimentConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete experiment' }),
    });
  }

  get rows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }
}
