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

  /** Locator for the dataset row by exact name, scoped to the data rowgroup. */
  datasetRow(name: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  /**
   * Click the name cell of the row matching the given dataset name. Resolves
   * to a DatasetItemsPage after the URL transitions to /datasets/<id>/items.
   */
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
    // Click the name cell (second td, after the select checkbox) — avoids the
    // row-action menu at the far right.
    await row.getByRole('cell', { name, exact: true }).click();
    await this.page.waitForURL((url) =>
      url.pathname.includes(`/datasets/${datasetId}/items`),
    );
    return new DatasetItemsPage(this.page, this.projectId, datasetId);
  }

  /** Open the side-panel "Create dataset" dialog. */
  async clickCreateDataset(): Promise<void> {
    await this.page.getByRole('button', { name: 'Create dataset' }).click();
    await this.createDialog.waitFor({ state: 'visible' });
    // The panel root is always mounted; "open" is signaled by transform
    // translateX(0px) rather than display/visibility.
    await this.page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="create-dataset-sidebar"]') as HTMLElement | null;
      const t = el?.style.transform ?? '';
      return t.includes('translateX(0');
    });
  }

  /** Fill the Step-1 fields of the create dialog. Does NOT submit. */
  async fillCreateDialog(args: { name: string; description?: string }): Promise<void> {
    await this.createDialog.getByRole('textbox', { name: 'Name' }).fill(args.name);
    if (args.description !== undefined) {
      await this.createDialog.getByRole('textbox', { name: 'Description' }).fill(args.description);
    }
  }

  /**
   * Submit the create dialog: Next on Step 1 → Create on Step 2 (which is the
   * CSV-or-SDK chooser; we create an empty dataset shell with no data) → Close
   * the success step. Resolves when the dialog closes and the new row appears
   * in the list.
   *
   * The dialog is three-step: name/description → add data → success. The
   * success step does NOT auto-close — we click the Close (X) button to
   * dismiss it.
   */
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
    // Panel stays mounted; "closed" = transform translateX(100%).
    await this.page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="create-dataset-sidebar"]') as HTMLElement | null;
      const t = el?.style.transform ?? '';
      return t.includes('translateX(100%)');
    });
  }

  /** Side-panel root — used to scope selectors inside the create dialog. */
  get createDialog(): Locator {
    return this.page.getByTestId('create-dataset-sidebar');
  }
}
