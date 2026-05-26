import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { DatasetItemsPage } from './dataset-items.page';

export class DatasetsPage {
  private projectId: string | null = null;

  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/datasets/`);
  }

  async waitForReady(): Promise<void> {
    const realRow = this.page.locator('tbody tr[data-row-id]').first();
    const emptyState = this.page.getByText('No datasets yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  datasetRow(name: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  async openDatasetByName(name: string): Promise<DatasetItemsPage> {
    if (!this.projectId) {
      throw new Error('DatasetsPage.openDatasetByName: call goto(projectId) first');
    }
    const row = this.datasetRow(name);
    await row.waitFor({ state: 'visible' });
    const datasetId = await row.getAttribute('data-row-id');
    if (!datasetId) {
      throw new Error(`DatasetsPage.openDatasetByName: row for "${name}" has no data-row-id`);
    }
    await row.getByRole('cell', { name, exact: true }).click();
    await this.page.waitForURL((url) =>
      url.pathname.includes(`/datasets/${datasetId}/items`),
    );
    return new DatasetItemsPage(this.page, this.projectId, datasetId);
  }

  async clickCreateDataset(): Promise<void> {
    await this.page.getByRole('button', { name: 'Create dataset' }).click();
    await this.createDialog.waitFor({ state: 'visible' });
    await this.waitForCreateDialogTransform('translateX(0');
  }

  async fillCreateDialog(args: { name: string; description?: string }): Promise<void> {
    await this.createDialog.getByRole('textbox', { name: 'Name' }).fill(args.name);
    if (args.description !== undefined) {
      await this.createDialog.getByRole('textbox', { name: 'Description' }).fill(args.description);
    }
  }

  /** Three-step dialog: name+description → CSV/SDK chooser → success. Success step doesn't auto-close. */
  async submitCreateDialog(): Promise<void> {
    await this.createDialog.getByRole('button', { name: 'Next' }).click();
    await this.createDialog
      .getByRole('heading', { name: 'Add data', level: 3 })
      .waitFor({ state: 'visible' });
    await this.createDialog.getByRole('button', { name: 'Create' }).click();
    await this.createDialog
      .getByRole('heading', { name: 'Dataset created!', level: 3 })
      .waitFor({ state: 'visible' });
    await this.createDialog.getByRole('button', { name: 'Close' }).click();
    await this.waitForCreateDialogTransform('translateX(100%)');
  }

  get createDialog(): Locator {
    return this.page.getByTestId('create-dataset-sidebar');
  }

  /** Panel stays mounted; open/closed state is animated via CSS transform, not display/visibility. */
  private async waitForCreateDialogTransform(value: string): Promise<void> {
    await this.page.waitForFunction((expected) => {
      const el = document.querySelector('[data-testid="create-dataset-sidebar"]') as HTMLElement | null;
      return (el?.style.transform ?? '').includes(expected);
    }, value);
  }
}
