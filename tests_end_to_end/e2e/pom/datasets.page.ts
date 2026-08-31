import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { DatasetItemsPage } from './dataset-items.page';

/**
 * Column ids of the list table, as `DatasetListPage` declares them. They are
 * also the second half of every `data-cell-id`, so a cell is addressed by
 * (dataset id, column id) rather than by position — column order is
 * user-configurable and persisted, so `nth-child` would be addressing whatever
 * the last user dragged into place.
 */
export const DATASET_COLUMN = {
  itemCount: 'dataset_items_count',
  mostRecentExperiment: 'most_recent_experiment_at',
  mostRecentOptimization: 'most_recent_optimization_at',
} as const;

export type DatasetColumnId = (typeof DATASET_COLUMN)[keyof typeof DATASET_COLUMN];

/** What a time column renders when the dataset has no such timestamp. */
export const EMPTY_CELL = '-';

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

  /**
   * One cell of one dataset's row, addressed by identity: the shared DataTable
   * stamps `data-row-id="<datasetId>"` on the row and
   * `data-cell-id="<datasetId>_<columnId>"` on the cell.
   *
   * Callers should assert `toHaveCount(1)` before reading it, so an ambiguous
   * match fails loudly instead of silently testing some other row.
   */
  cell(datasetId: string, columnId: DatasetColumnId): Locator {
    return this.page.locator(
      `tbody tr[data-row-id="${datasetId}"] td[data-cell-id="${datasetId}_${columnId}"]`,
    );
  }

  /**
   * Turn on a column that is not in the default selection, via the Columns
   * picker.
   *
   * Selection lives in localStorage, so a fresh browser context always starts
   * from the default set — a test that needs an optional column must enable it
   * rather than assume a previous run left it on.
   *
   * The entries are Radix checkbox menu items, but `SortableMenuItem` spreads
   * dnd-kit's sortable attributes over them, and those set `role="button"`.
   * So each one reports as a button (named for the column) wrapping the
   * checkbox that carries the state — not as a `menuitemcheckbox`.
   */
  async showColumn(label: string): Promise<void> {
    return test.step(`Enable the "${label}" column`, async () => {
      await this.page.getByTestId('columns-button').click();
      const menu = this.page.getByRole('menu');
      await menu.waitFor({ state: 'visible' });

      const entry = menu.getByRole('button', { name: label, exact: true });
      const toggle = entry.getByRole('checkbox');
      if (!(await toggle.isChecked())) {
        await entry.click();
      }
      // Assert rather than assume: a picker that silently failed to select the
      // column would leave the caller asserting an empty cell that never existed.
      await expect(toggle, `"${label}" is selected in the Columns picker`).toBeChecked();

      // Selecting keeps the menu open (onSelect is prevented), so close it
      // rather than leaving it over the table.
      await this.page.keyboard.press('Escape');
      await menu.waitFor({ state: 'hidden' });
    });
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
