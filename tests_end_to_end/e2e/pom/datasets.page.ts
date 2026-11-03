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

  /**
   * The "Item count" cell of a dataset's row, as rendered.
   *
   * Addressed by the table's own `data-cell-id` (`<rowId>_<columnId>`) rather
   * than a positional nth(): the column is user-configurable in both order and
   * visibility, so position is not stable. `dataset_items_count` is the column
   * id `DatasetListPage` registers, and it is in the default selected set.
   *
   * Unlike the Version history tab's Item count, this column has no
   * `accessorFn`, so the number is rendered raw — "2500", not "2,500".
   */
  datasetItemCount(name: string): Locator {
    return this.datasetRow(name).locator('[data-cell-id$="_dataset_items_count"]');
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

  // --- "Upload a file" mode of the create sidebar ---

  /**
   * Open the create sidebar in UPLOAD mode — the sibling of
   * {@link clickCreateDataset}, which opens it in SDK mode.
   *
   * Handles both entry points for the same reason that one does: the empty state
   * renders the "Upload a file" card directly, and once the list has rows the
   * header "Create dataset" button is a dropdown trigger offering the same two
   * options (`CreateEntityMenu`). A spec that assumed either would work on a
   * fresh workspace and break on a populated one.
   */
  async clickUploadAFile(): Promise<void> {
    return test.step('Open the create-dataset sidebar in upload mode', async () => {
      const emptyStateCard = this.page.getByRole('button', { name: 'Upload a file' });
      if (await emptyStateCard.count()) {
        await emptyStateCard.first().click();
      } else {
        await this.page.getByRole('button', { name: 'Create dataset' }).click();
        await this.page.getByRole('menuitem', { name: 'Upload a file' }).click();
      }
      await this.createDialog.waitFor({ state: 'visible' });
      await this.waitForCreateDialogTransform('translateX(0');
    });
  }

  /**
   * The dropzone's hidden `<input type="file">`, scoped to the sidebar.
   *
   * `DatasetCsvDropzone` renders it with `className="hidden"` and no testid, so
   * it is addressed by type. Playwright's `setInputFiles` does not require the
   * input to be visible, which is what makes driving the real control possible
   * without synthesising a drag event.
   */
  private get uploadFileInput(): Locator {
    return this.createDialog.locator('input[type="file"]');
  }

  /**
   * Attach `filePath` through the dropzone and wait for the sidebar to have
   * taken it.
   *
   * The settle point is the Name field appearing. That is not an arbitrary
   * proxy: `CreateDatasetSidebar` renders the name and description fields only
   * once `uploadFile !== undefined && !uploadError`, so the field's presence IS
   * the form reporting that the file passed CLIENT-side validation
   * (`validateDatasetUploadFile` — extension and size) and that the submit
   * button will now be enabled. A caller that typed into the name field straight
   * after attaching would otherwise race that render.
   *
   * Waiting for it also separates the two kinds of rejection a spec about this
   * flow has to keep apart. A file the CLIENT refuses
   * (`validateDatasetUploadFile`: wrong extension, over the size cap) leaves
   * `uploadFile` undefined, renders an inline `role="alert"` instead of the name
   * field, and never sends anything to the server — so if that happened, this
   * assertion fails here rather than letting a spec about a SERVER rejection
   * pass on a file that never left the browser.
   */
  async attachUploadFile(filePath: string): Promise<void> {
    return test.step(`Attach "${filePath}" to the upload dropzone`, async () => {
      await this.uploadFileInput.setInputFiles(filePath);
      await expect(
        this.uploadNameField,
        'the sidebar accepted the file client-side and asked for a name — a client-side ' +
          'rejection renders an inline error here instead and sends nothing',
      ).toBeVisible();
    });
  }

  /**
   * The Name field of the upload form.
   *
   * Only rendered once a file has passed client validation, and pre-filled by
   * `handleFileSelect` from the file's own base name plus today's local date
   * (`appendDateToAutoName`). A spec that needs a predictable dataset name
   * therefore overwrites it rather than inferring what the derivation produced —
   * and a spec that does not must still name its FILE inside the run prefix, or
   * the dataset the sidebar creates escapes the swept namespace.
   */
  get uploadNameField(): Locator {
    return this.createDialog.getByRole('textbox', { name: 'Name' });
  }

  /** The name the sidebar derived from the attached file, as pre-filled. */
  async readDerivedUploadName(): Promise<string> {
    return test.step('Read the name the sidebar derived from the file', async () => {
      await expect(this.uploadNameField).toBeVisible();
      return this.uploadNameField.inputValue();
    });
  }

  /** The sidebar's footer submit, which reads "Create dataset" in both modes. */
  get submitUploadButton(): Locator {
    return this.createDialog.getByRole('button', { name: 'Create dataset' });
  }

  /**
   * Submit the upload form.
   *
   * Does NOT wait for the sidebar to close, and does not assert an outcome:
   * whether the upload is accepted or rejected is the caller's assertion, the
   * two paths close the panel at different moments, and a POM that waited for
   * either would be deciding the answer. Callers subscribe to the upload
   * response BEFORE calling this — `useDatasetForm` fires the create and the
   * upload back to back, so subscribing afterwards races them.
   */
  async submitUpload(): Promise<void> {
    return test.step('Submit the upload form', async () => {
      await expect(
        this.submitUploadButton,
        'the submit is enabled, so the form considers the file and name valid',
      ).toBeEnabled();
      await this.submitUploadButton.click();
    });
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
