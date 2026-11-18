import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { ItemReturnNav } from './item-return-nav.page';
import { VersionRecordsSidebarPage } from './version-records-sidebar.page';

/**
 * One "name → value" row of the record panel, as it is RENDERED.
 *
 * The panel is the only place three facts about a row are visible at once, and
 * each of them is a different claim:
 *
 *  - `name` is the Field name box's current content, which is what a rename
 *    changes and what the stored key is supposed to become;
 *  - `value` is the value textarea's content, which after a commit is the only
 *    place a STALE in-session snapshot shows itself — the grid cell beside it
 *    reads from the server;
 *  - `message` is the validation line under the row, `null` when the row has
 *    none.
 *
 * Read as data rather than asserted through a locator because the rows are
 * only tellable apart by their content: every row carries the same
 * `dataset-field-row` testid and the same `Field name` label, and after an
 * invalid rename a row's value box loses the accessible name (`<field> value`)
 * that would otherwise identify it. A spec therefore compares the whole
 * panel — which is also what catches a validation error landing on the row the
 * user never touched.
 */
export interface RenderedFieldRow {
  name: string;
  value: string;
  message: string | null;
}

export class DatasetItemsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
    private readonly datasetId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('Open dataset items page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/datasets/${this.datasetId}/items`,
      );
    });
  }

  /**
   * Open the items page the way a link from an experiment does: with the
   * originating compare view in `from`, and optionally a `row` to expand.
   *
   * `from` is passed through `URLSearchParams`, which encodes the whole href —
   * query and all — into the one param, exactly as the product's own `Link`
   * does. Writing it by hand is what lets a spec drive the reject cases (an
   * off-site URL, a protocol-relative one), which no in-app link would ever
   * produce.
   *
   * `row` is left off entirely when not asked for, rather than sent empty: the
   * page treats an absent `row` as "no panel", and `row=` is a different thing.
   */
  async gotoWithReturn(opts: { from?: string | null; row?: string } = {}): Promise<void> {
    return test.step('Open dataset items page with a return href', async () => {
      const env = loadEnvConfig();
      const url = new URL(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/datasets/${this.datasetId}/items`,
      );
      if (opts.row) url.searchParams.set('row', opts.row);
      if (opts.from) url.searchParams.set('from', opts.from);
      await this.page.goto(url.toString());
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for Records tab ready', async () => {
      await this.page.getByRole('tab', { name: 'Records', selected: true }).waitFor({ state: 'visible' });
      const realRow = this.itemsTableBody.locator('tr[data-row-id]').first();
      const emptyState = this.page.getByText('No records yet');
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /** Rendered row count on the current page. Table is paginated (default 10/page); the "Showing X of Y" text is stale during drafts. */
  async countItems(): Promise<number> {
    return test.step('Read dataset item count', async () => {
      return this.itemsTableBody.locator('tr[data-row-id]').count();
    });
  }

  draftBadge(): Locator {
    return this.page.getByText('Draft', { exact: true });
  }

  versionLabel(version: number): Locator {
    return this.page.getByText(`v${version}`, { exact: true });
  }

  /**
   * Every rendered item row. Exposed so callers can use a retrying
   * `toHaveCount(...)` instead of the one-shot `countItems()`, which reads
   * whatever happens to be in the DOM at that instant.
   */
  itemRows(): Locator {
    return this.itemsTableBody.locator('tr[data-row-id]');
  }

  itemRow(index: number): Locator {
    return this.itemsTableBody.locator('tr[data-row-id]').nth(index);
  }

  itemRowById(id: string): Locator {
    return this.itemsTableBody.locator(`tr[data-row-id="${id}"]`);
  }

  /**
   * One item's cell for one of its data fields.
   *
   * Addressed by the table's own `data-cell-id` (`<rowId>_<columnId>`) rather
   * than by position: dataset item columns are derived from the item's own
   * keys, and their order is user-configurable and persisted, so an
   * `nth-child` would read a different field the moment someone reorders the
   * grid.
   *
   * The id is `<itemId>_data_<field>`, not `<itemId>_data.<field>`: the FE
   * builds the column id as `data.<field>` (`COLUMN_DATA_ID`) and hands it to
   * TanStack as an `accessorKey` with no explicit `id` (`convertColumnDataToColumn`
   * in `lib/table.ts`), so TanStack derives the column id by replacing EVERY dot
   * with an underscore. So neither half of this is the field name the SDK sent.
   *
   * That replacement is applied here to the whole `data.<field>` string rather
   * than only to the separator, because a dataset field name may itself contain
   * a dot — item content is arbitrary JSON. Addressing `a.b` as `_data_a.b`
   * would match nothing, since the table stamps `_data_a_b`.
   *
   * Note that the FE's normalisation is lossy: fields `a.b` and `a_b` both
   * become `data_a_b`, so an item carrying both cannot be addressed cell by
   * cell through this helper at all. Disambiguating that is a front-end
   * decision about the cell-id scheme, not something a page object can fix.
   *
   * The whole id is escaped before it goes into the CSS attribute selector.
   * Unlike `itemRowById`, whose argument is always a server-issued uuid, a
   * dataset field name is user data — any JSON key is legal, and a `"` or a
   * `\` in one would otherwise end the quoted string early and silently match
   * a different cell, or none.
   */
  itemCell(itemId: string, field: string): Locator {
    const columnId = `data.${field}`.replace(/\./g, '_');
    const cellId = `${itemId}_${columnId}`.replace(/["\\]/g, '\\$&');
    return this.itemRowById(itemId).locator(`[data-cell-id="${cellId}"]`);
  }

  /**
   * The dataset item ids the grid is currently rendering, in row order.
   *
   * Read from `data-row-id` rather than from cell text: a caller checking which
   * rows survived a scoped mutation needs identity, and the visible columns are
   * user-configurable. Single page only — the table paginates at 10 by default,
   * so a caller asserting an exact set must keep the fixture under a page.
   */
  async itemRowIds(): Promise<string[]> {
    return test.step('Read rendered dataset item ids', async () => {
      return this.itemsTableBody
        .locator('tr[data-row-id]')
        .evaluateAll((rows) => rows.map((r) => r.getAttribute('data-row-id') ?? ''));
    });
  }

  async clickAddItem(): Promise<void> {
    return test.step('Open add-item panel', async () => {
      await this.page.getByTestId('dataset-header-add-button').click();
      await this.addItemPanel.waitFor({ state: 'visible' });
      await this.waitForPanelTransform('translateX(0');
    });
  }

  /** Clicking a row opens the editor side-panel (URL gains ?row=<id>). */
  async openItemEditor(index: number): Promise<void> {
    return test.step(`Open editor for item at index ${index}`, async () => {
      const row = this.itemRow(index);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('cell').nth(1).click();
      await this.editItemPanel.waitFor({ state: 'visible' });
      await this.editItemPanel.getByTestId('dataset-field-row').first().waitFor({ state: 'visible' });
    });
  }

  /**
   * The same open, with the row addressed by identity.
   *
   * Row ORDER is not something a seed can promise — the items endpoint does not
   * specify one — so a spec that edits one of several records and asserts the
   * others were left alone has to say which row it opened. Identity comes from
   * the table's own `data-row-id`; the cell index only picks something inside
   * that row to click, and it is 1 rather than 0 because the first cell is the
   * select checkbox, which toggles selection instead of opening the panel.
   *
   * The row lookup is asserted to resolve to exactly one element rather than
   * taking `.first()`, so a grid rendering an id twice fails here instead of
   * silently editing whichever copy matched first.
   */
  async openItemEditorById(itemId: string): Promise<void> {
    return test.step(`Open editor for item ${itemId}`, async () => {
      const row = this.itemRowById(itemId);
      await expect(row, `exactly one row for item ${itemId}`).toHaveCount(1);
      await row.getByRole('cell').nth(1).click();
      await this.editItemPanel.waitFor({ state: 'visible' });
      await this.editItemPanel.getByTestId('dataset-field-row').first().waitFor({ state: 'visible' });
    });
  }

  /**
   * Edits a single field in the open editor. The editor autosaves: changing a
   * field stages an "edited" draft (Draft badge appears), it does not persist
   * on its own — commitDraft() turns the draft into a new version.
   */
  async editItemField(field: string, value: string): Promise<void> {
    return test.step(`Edit field "${field}"`, async () => {
      await this.fieldValue(this.editItemPanel, field).fill(value);
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /**
   * Renames one field of the open record, leaving its value alone.
   *
   * The row is resolved BEFORE the name box is touched, and every later locator
   * is scoped to that row. Both of the row's inputs are addressed by the field
   * name — the value textarea's accessible name is `<field> value` and there is
   * one `Field name` box per row — so a lookup repeated after the rename would
   * be looking for a label that no longer exists. Asserted to resolve to
   * exactly one row rather than taking `.first()`, so an ambiguous field name
   * fails loudly instead of renaming a different field.
   *
   * Like `editItemField`, this only stages a draft: `commitDraft()` is what
   * turns it into a new version.
   */
  async renameItemField(field: string, newName: string): Promise<void> {
    return test.step(`Rename field "${field}" to "${newName}"`, async () => {
      const row = this.fieldRow(this.editItemPanel, field);
      await expect(row, `exactly one "${field}" field row`).toHaveCount(1);
      await row.getByLabel('Field name', { exact: true }).fill(newName);
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /**
   * Adds a field to the open record through the panel's "Add field" button.
   *
   * Named first, then filled, in that order: the value box's accessible name is
   * derived from the field's name (`Field value` while it is blank), so the
   * stable handle for it only exists once the name does. The new row is the
   * last one — the editor appends — and that is also the row "Add field"
   * autofocuses, so the name box is addressed as the last of the panel rather
   * than by a position chosen in advance.
   *
   * The draft badge is waited for afterwards rather than between the two fills:
   * `DatasetItemFieldsAutosaveForm` deliberately does NOT mark the record
   * edited for a field that has a name but no value yet, because
   * `fieldRowsToData` skips a blank-named row and the resulting data is
   * unchanged.
   */
  async addItemField(name: string, value: string): Promise<void> {
    return test.step(`Add field "${name}" to the open record`, async () => {
      await this.editItemPanel.getByRole('button', { name: 'Add field', exact: true }).click();
      await this.editItemPanel.getByLabel('Field name', { exact: true }).last().fill(name);
      await this.fieldValue(this.editItemPanel, name).fill(value);
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /**
   * One data column's header on the Records grid.
   *
   * Addressed by the shared `DataTable`'s own `data-header-id`, which is the
   * TanStack column id — `data_<field>`, with every dot replaced, for the same
   * reason `itemCell` documents at length. Position is not usable here: column
   * order is user-configurable and persisted.
   *
   * This is the only place a field introduced by an UNCOMMITTED draft is
   * observable at all. The items endpoint reports the saved version's columns,
   * so `useColumnsWithDraftKeys` is what merges a draft's new keys in — and a
   * regression there is invisible in the API.
   */
  itemColumnHeader(field: string): Locator {
    const columnId = `data.${field}`.replace(/\./g, '_');
    const headerId = columnId.replace(/["\\]/g, '\\$&');
    return this.page
      .getByRole('tabpanel', { name: 'Records' })
      .locator(`th[data-header-id="${headerId}"]`);
  }

  /**
   * Every row of the OPEN record panel, in render order, as `RenderedFieldRow`.
   *
   * Render order is the panel's own: `dataToFieldRows` lists the dataset's
   * schema columns first (the items endpoint returns them alphabetically) and
   * then any key only this record has. A spec comparing the whole array is
   * therefore also asserting that order, which is what makes a duplicate-name
   * assertion precise — `getFieldRowErrors` walks the rows in exactly this
   * order and gives the name to the first claimant, so WHICH row ends up
   * flagged is a consequence of it.
   *
   * The panel is asserted to hold at least one row first, so an empty answer
   * means "no fields" rather than "called before the panel mounted".
   */
  async readItemEditorRows(): Promise<RenderedFieldRow[]> {
    return test.step('Read the record panel\'s field rows as rendered', async () => {
      const rows = this.editItemPanel.getByTestId('dataset-field-row');
      await expect(rows.first(), 'the record panel renders at least one field row').toBeVisible();
      return rows.evaluateAll((elements) =>
        elements.map((element) => {
          const name = element.querySelector('input');
          const value = element.querySelector('textarea');
          // `:scope > p` rather than any descendant `p`: the validation line is
          // `FieldRowShell`'s `message` slot, a direct child of the row's outer
          // div, and scoping it there keeps a future `note` slot from reading
          // as an error.
          const message = element.querySelector(':scope > p');
          return {
            name: (name as HTMLInputElement | null)?.value ?? '',
            value: (value as HTMLTextAreaElement | null)?.value ?? '',
            message: message === null ? null : (message.textContent ?? '').trim(),
          };
        }),
      );
    });
  }

  async closeItemEditor(): Promise<void> {
    return test.step('Close editor side-panel', async () => {
      await this.editItemPanel.getByRole('button', { name: 'Close' }).click();
    });
  }

  async selectRow(index: number): Promise<void> {
    return test.step(`Select row at index ${index}`, async () => {
      await this.itemRow(index).getByRole('checkbox', { name: 'Select row' }).click();
    });
  }

  /**
   * Bulk-deletes the currently selected rows. For an explicit selection this
   * stages a draft (no confirmation dialog) — commitDraft() persists it.
   */
  async bulkDeleteSelected(): Promise<void> {
    return test.step('Bulk-delete selected rows', async () => {
      await this.page.getByTestId('dataset-items-bulk-delete-button').click();
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /**
   * Back to the first tab. Needed after the Version history tab, because
   * `waitForReady` waits on the Records tab being *selected* and the tab choice
   * is persisted in the URL.
   */
  async openRecords(): Promise<void> {
    return test.step('Open the Records tab', async () => {
      await this.page.getByRole('tab', { name: 'Records' }).click();
      await this.waitForReady();
    });
  }

  /** Second tab of the dataset page: one row per committed version. */
  async openVersionHistory(): Promise<void> {
    return test.step('Open the Version history tab', async () => {
      await this.page.getByRole('tab', { name: 'Version history' }).click();
      const realRow = this.versionsTableBody.locator('tr[data-row-id]').first();
      const emptyState = this.page.getByText('No version history yet');
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  versionHistoryRow(versionName: string): Locator {
    return this.versionsTableBody
      .locator('tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name: versionName, exact: true }) });
  }

  /**
   * Open one version's read-only records sheet by clicking its row.
   *
   * Only a NON-latest row opens: the latest version's row is deliberately
   * inert, its click handler returning early. The row lookup is asserted to
   * resolve to exactly one element rather than taking `.first()`, so an
   * ambiguous version name fails loudly instead of opening whichever row the
   * grid happened to render first.
   */
  async openVersionRecords(versionName: string): Promise<VersionRecordsSidebarPage> {
    return test.step(`Open version ${versionName}'s records sheet`, async () => {
      const row = this.versionHistoryRow(versionName);
      await expect(row).toHaveCount(1);
      await row.click();
      const sidebar = new VersionRecordsSidebarPage(this.page);
      await sidebar.waitForReady();
      return sidebar;
    });
  }

  /**
   * The "Item count" cell of a version row, as rendered — thousands-separated
   * ("1,800"), because the column formats through toLocaleString().
   *
   * Addressed by the table's own `data-cell-id` (`<rowId>_<columnId>`) rather
   * than a positional nth(): the version table has no per-cell testid, and
   * column order is user-configurable, so position is not stable.
   */
  versionItemCount(versionName: string): Locator {
    return this.versionHistoryRow(versionName).locator('[data-cell-id$="_items_total"]');
  }

  /**
   * The "Changes" cell of a version row: the added / modified / deleted tags
   * the column renders, e.g. `+ 1` for a version that added one item and
   * changed nothing else, or `-` for a version with no counted change.
   *
   * Addressed by `data-cell-id` for the same reason as `versionItemCount`.
   * This is the only place the per-version added/modified split is visible to
   * a user — "Item count" alone cannot tell a version that added one item
   * apart from one that added two and deleted one.
   */
  versionChangeSummary(versionName: string): Locator {
    return this.versionHistoryRow(versionName).locator('[data-cell-id$="_change_summary"]');
  }

  async search(term: string): Promise<void> {
    return test.step(`Search items for "${term}"`, async () => {
      await this.page.getByTestId('search-input').fill(term);
    });
  }

  async clearSearch(): Promise<void> {
    return test.step('Clear item search', async () => {
      await this.page.getByTestId('search-input').fill('');
    });
  }

  /**
   * Each field is a "name → value" row; the value box is labelled "<name> value".
   * Fields the dataset doesn't have yet (always the case for an empty dataset)
   * are added with "Add field" and named first.
   */
  async fillAddItemPanel(fields: Record<string, string>): Promise<void> {
    return test.step('Fill add-item panel', async () => {
      for (const [name, value] of Object.entries(fields)) {
        const valueBox = this.fieldValue(this.addItemPanel, name);
        if ((await valueBox.count()) === 0) {
          await this.addItemPanel.getByRole('button', { name: 'Add field', exact: true }).click();
          await this.addItemPanel.getByLabel('Field name', { exact: true }).last().fill(name);
        }
        await valueBox.fill(value);
      }
    });
  }

  /** Stages the new item as a draft; commitDraft() persists it. */
  async submitAddItemPanel(): Promise<void> {
    return test.step('Submit add-item panel (stage draft)', async () => {
      await this.addItemPanel.getByRole('button', { name: 'Add record', exact: true }).click();
      await this.waitForPanelTransform('translateX(100%)');
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /** Commits all staged drafts as a new dataset version via the confirmation modal. */
  async commitDraft(opts: { versionNote?: string } = {}): Promise<void> {
    return test.step('Commit draft as new version', async () => {
      await this.pageLevelCommitButton().click();
      const modal = await this.versionCommitModal();
      await modal.waitFor({ state: 'visible' });
      if (opts.versionNote !== undefined) {
        await modal.getByRole('textbox', { name: 'Version note (optional)' }).fill(opts.versionNote);
      }
      await modal.getByRole('button', { name: 'Save changes' }).click();
      await modal.waitFor({ state: 'hidden' });
      await this.draftBadge().waitFor({ state: 'hidden' });
    });
  }

  async discardDraft(): Promise<void> {
    return test.step('Discard draft', async () => {
      const button = await this.preferTestid('dataset-items-discard-button', { role: 'button', name: 'Discard changes' });
      await button.click();
      await this.draftBadge().waitFor({ state: 'hidden' });
    });
  }

  /** Confirmation dialog title reads "Remove suite items" — component is shared with Test Suites. */
  async deleteItemByIndex(index: number): Promise<void> {
    return test.step(`Delete dataset item at index ${index}`, async () => {
      const row = this.itemRow(index);
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();
      const dialog = this.page.getByRole('dialog', { name: 'Remove suite items' });
      await dialog.waitFor({ state: 'visible' });
      await dialog.getByRole('button', { name: 'Remove suite items' }).click();
      await dialog.waitFor({ state: 'hidden' });
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /**
   * An empty dataset opens the same add panel as a populated one, starting with
   * no fields. The record is staged as a draft like any other, so this also
   * commits it as the dataset's first version.
   */
  async addFirstItem(fields: Record<string, string>): Promise<void> {
    return test.step('Add the first item to an empty dataset', async () => {
      await this.clickAddItem();
      await this.fillAddItemPanel(fields);
      await this.submitAddItemPanel();
      await this.commitDraft();
      await this.itemsTableBody.locator('tr[data-row-id]').first().waitFor({ state: 'visible' });
    });
  }

  private fieldValue(scope: Page | Locator, field: string): Locator {
    return scope.getByLabel(`${field} value`, { exact: true });
  }

  /**
   * One "name → value" row of a record panel, located by the field it holds.
   *
   * Every row carries the same `dataset-field-row` testid and the same
   * `Field name` label, so the field's own value box — whose accessible name is
   * `<field> value` — is the only thing that tells the rows apart.
   *
   * The `has:` locator is rooted at the PAGE rather than at `panel`: Playwright
   * anchors an inner locator's whole selector chain at each outer match, so a
   * panel-rooted one would look for the panel inside the row and match nothing.
   */
  private fieldRow(panel: Locator, field: string): Locator {
    return panel
      .getByTestId('dataset-field-row')
      .filter({ has: this.fieldValue(this.page, field) });
  }

  get addItemPanel(): Locator {
    return this.page.getByTestId('dataset-item-panel');
  }

  get editItemPanel(): Locator {
    return this.page.getByTestId('dataset-item-editor');
  }

  /**
   * Wait for the item detail panel to be mounted and return the return-nav
   * controls scoped to it.
   *
   * The wait belongs here rather than in the caller: `ViewInExperimentButton`
   * renders inside this panel, so asking whether the button is absent before
   * the panel exists would answer "absent" for every build.
   */
  async itemPanelReturnNav(): Promise<ItemReturnNav> {
    return test.step('Wait for the item detail panel', async () => {
      await this.editItemPanel.waitFor({ state: 'visible' });
      return new ItemReturnNav(this.page, this.editItemPanel);
    });
  }

  /** The return-nav controls for the header alone, with no panel open. */
  headerReturnNav(): ItemReturnNav {
    return new ItemReturnNav(this.page, this.editItemPanel);
  }

  private get itemsTableBody(): Locator {
    return this.page.getByRole('tabpanel', { name: 'Records' }).locator('tbody');
  }

  private get versionsTableBody(): Locator {
    return this.page.getByRole('tabpanel', { name: 'Version history' }).locator('tbody');
  }

  /** Panel stays mounted; open/closed is animated via CSS transform, not display/visibility. */
  private async waitForPanelTransform(value: string): Promise<void> {
    await this.page.waitForFunction((expected) => {
      const el = document.querySelector('[data-testid="dataset-item-panel"]') as HTMLElement | null;
      return (el?.style.transform ?? '').includes(expected);
    }, value);
  }

  private pageLevelCommitButton(): Locator {
    const byTestid = this.page.getByTestId('dataset-items-commit-button');
    return byTestid.or(this.page.getByRole('button', { name: 'Save changes' })).first();
  }

  private async versionCommitModal(): Promise<Locator> {
    const byTestid = this.page.getByTestId('dataset-version-commit-dialog');
    if ((await byTestid.count()) > 0) return byTestid;
    return this.page.getByRole('dialog', { name: 'Save changes' });
  }

  private async preferTestid(testid: string, role: { role: 'button'; name: string }): Promise<Locator> {
    const byTestid = this.page.getByTestId(testid);
    if ((await byTestid.count()) > 0) return byTestid;
    return this.page.getByRole(role.role, { name: role.name });
  }
}
