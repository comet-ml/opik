import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { DatasetItemsPage } from './dataset-items.page';

/**
 * Column ids on the Datasets list, as `DatasetListPage`'s `DEFAULT_COLUMNS`
 * declares them. The DataTable stamps `data-cell-id="<rowId>_<columnId>"`, so
 * these are how a cell is addressed by identity — column ORDER is
 * user-configurable and persisted per workspace, which makes any positional
 * selector (`td:nth-child(4)`) wrong for a different user on the same page.
 */
export type DatasetColumnId =
  | 'name'
  | 'description'
  | 'dataset_items_count'
  | 'most_recent_experiment_at'
  | 'most_recent_optimization_at'
  | 'last_updated_at';

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
   * A single cell of a dataset's row, addressed by dataset id and column id
   * rather than by position — `data-cell-id` is `<rowId>_<columnId>`, and the
   * row id is the dataset id (`getRowId` on the page).
   *
   * Takes the id rather than the name because a caller reading a computed
   * column already knows which dataset it seeded, and an id cannot be
   * ambiguous the way a name-matching filter can.
   */
  datasetCell(datasetId: string, column: DatasetColumnId): Locator {
    return this.page.locator(
      `tbody tr[data-row-id="${datasetId}"] [data-cell-id="${datasetId}_${column}"]`,
    );
  }

  /**
   * The rendered text of one cell, trimmed.
   *
   * Asserts the locator resolved to exactly one element first: a duplicated
   * row or a column rendered twice would otherwise be silently reduced to
   * whichever matched first, and the test would report on a cell it never
   * meant to read.
   */
  async datasetCellText(datasetId: string, column: DatasetColumnId): Promise<string> {
    return test.step(`read the ${column} cell of dataset ${datasetId}`, async () => {
      const cell = this.datasetCell(datasetId, column);
      await expect(cell).toHaveCount(1);
      return (await cell.innerText()).trim();
    });
  }

  /** The Columns dropdown trigger in the list toolbar. */
  get columnsButton(): Locator {
    return this.page.getByTestId('columns-button');
  }

  /**
   * Turn a column on or off through the Columns dropdown, by its visible label.
   *
   * Idempotent, and asserts the checkbox's starting state before clicking, so a
   * regression that renders the menu out of sync with the table fails here
   * rather than quietly toggling the column the wrong way.
   *
   * Each row is a Radix `CheckboxItem`, but `SortableMenuItem` spreads dnd-kit's
   * sortable attributes over it, and those set `role="button"` — so the row is
   * addressed as a button and its state read from the checkbox it wraps, not
   * from a `menuitemcheckbox` role that never reaches the DOM. Neither carries
   * a data-testid. `exact` matching is required because "Most recent
   * experiment" and "Most recent optimization" share a prefix.
   */
  async setColumnEnabled(label: string, enabled: boolean): Promise<void> {
    return test.step(`set column "${label}" enabled=${enabled}`, async () => {
      await this.columnsButton.click();
      const menu = this.page.getByRole('menu');
      const item = menu.getByRole('button', { name: label, exact: true });
      await expect(item).toHaveCount(1);
      const checkbox = item.getByRole('checkbox');
      if ((await checkbox.isChecked()) !== enabled) {
        await item.click();
      }
      await expect(checkbox).toBeChecked({ checked: enabled });
      // The menu keeps itself open on select (`onSelect` is prevented) so a
      // caller can toggle several columns; close it explicitly rather than
      // leaving an overlay across the table the next assertion has to read.
      await this.page.keyboard.press('Escape');
      await expect(item).toBeHidden();
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
