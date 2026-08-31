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

  /** Every rendered dataset row — the count a spec asserts the page is complete by. */
  get datasetRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /**
   * A row addressed by dataset id (the DataTable's `data-row-id`) rather than
   * by name. Preferred when the spec already holds the id from a fixture: it
   * cannot match a second dataset whose name merely contains this one.
   */
  datasetRowById(datasetId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${datasetId}"]`);
  }

  columnHeader(label: string): Locator {
    return this.page.getByRole('columnheader', { name: label, exact: true });
  }

  /**
   * The "Item count" cell of a dataset's row, addressed by the table's own
   * `data-cell-id` (`<rowId>_<columnId>`). The rows carry no per-cell testid
   * and column order is user-configurable and persisted in localStorage, so a
   * positional nth() would be neither stable nor portable. `dataset_items_count`
   * is the column id DatasetListPage.tsx declares for it.
   */
  itemCountCell(datasetId: string): Locator {
    return this.datasetRowById(datasetId).locator('[data-cell-id$="_dataset_items_count"]');
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

  /**
   * Opens the create sidebar in SDK mode. The empty state shows "Upload a file"
   * and "Use SDK" cards directly; once the list has rows the header button is a
   * dropdown trigger with the same two options. Handle both so the method works
   * regardless of list state.
   */
  async clickCreateDataset(): Promise<void> {
    const emptyStateUseSdk = this.page.getByRole('button', { name: 'Use SDK' });
    if (await emptyStateUseSdk.count()) {
      await emptyStateUseSdk.first().click();
    } else {
      await this.page.getByRole('button', { name: 'Create dataset' }).click();
      await this.page.getByRole('menuitem', { name: 'Use SDK' }).click();
    }
    await this.createDialog.waitFor({ state: 'visible' });
    await this.waitForCreateDialogTransform('translateX(0');
  }

  async fillCreateDialog(args: { name: string; description?: string }): Promise<void> {
    await this.createDialog.getByRole('textbox', { name: 'Name' }).fill(args.name);
    if (args.description !== undefined) {
      await this.createDialog
        .getByRole('textbox', { name: 'Description (optional)' })
        .fill(args.description);
    }
  }

  /** SDK-mode create: footer "Create dataset" submits, then the sidebar closes (no success step). */
  async submitCreateDialog(): Promise<void> {
    await this.createDialog.getByRole('button', { name: 'Create dataset' }).click();
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
