import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export class DatasetItemsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
    private readonly datasetId: string,
  ) {}

  async goto(): Promise<void> {
    const env = loadEnvConfig();
    await this.page.goto(
      `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/datasets/${this.datasetId}/items`,
    );
  }

  async waitForReady(): Promise<void> {
    await this.page.getByRole('tab', { name: 'Items', selected: true }).waitFor({ state: 'visible' });
    const realRow = this.itemsTableBody.locator('tr[data-row-id]').first();
    // Empty-state copy is "No <entityName> items yet" — for datasets that's
    // "No dataset items yet".
    const emptyState = this.page.getByText('No dataset items yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  /**
   * Count of item rows currently rendered in the items table body. While the
   * page is in Draft mode, the "Showing X-Y of Z" text reflects the
   * pre-draft committed count and is stale; the rendered row count is the
   * source of truth for assertions during a test.
   */
  async countItems(): Promise<number> {
    return this.itemsTableBody.locator('tr[data-row-id]').count();
  }

  /** True iff the page header shows the "Draft" badge. */
  draftBadge(): Locator {
    return this.page.getByText('Draft', { exact: true });
  }

  /** Locator matching the page header version label (`v1`, `v2`, ...). */
  versionLabel(version: number): Locator {
    return this.page.getByText(`v${version}`, { exact: true });
  }

  /** Item row by zero-based index in the visible table body. */
  itemRow(index: number): Locator {
    return this.itemsTableBody.locator('tr[data-row-id]').nth(index);
  }

  /** Item row by its dataset_item id. */
  itemRowById(id: string): Locator {
    return this.itemsTableBody.locator(`tr[data-row-id="${id}"]`);
  }

  /**
   * Open the "Add item" side-panel. Waits for the panel to be on-screen.
   *
   * Prefers the dataset-items-add-button testid (added in this PR). Falls
   * back to role+name "Add item" so the test passes against deployments
   * that don't yet carry the new FE testid.
   */
  async clickAddItem(): Promise<void> {
    const byTestid = this.page.getByTestId('dataset-items-add-button');
    const byRole = this.page.getByRole('button', { name: 'Add item' });
    const button = (await byTestid.count()) > 0 ? byTestid : byRole;
    await button.click();
    await this.addItemPanel.waitFor({ state: 'visible' });
    // The panel root is always mounted in the DOM; "open" is signaled by
    // transform: translateX(0px) rather than by display/visibility.
    await this.page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="dataset-item-panel"]') as HTMLElement | null;
      const t = el?.style.transform ?? '';
      return t.includes('translateX(0');
    });
  }

  /**
   * Fill the open Add-item panel's column textareas. Pass one entry per
   * dataset column; the FE auto-detects the schema from existing items.
   */
  async fillAddItemPanel(fields: Record<string, string>): Promise<void> {
    for (const [column, value] of Object.entries(fields)) {
      await this.addItemPanel
        .getByRole('region', { name: column })
        .getByPlaceholder('Enter text for this field…')
        .fill(value);
    }
  }

  /**
   * Click the panel-level "Save changes" — stages the new item as a draft and
   * adds it to the rendered table. The change is NOT yet committed to the
   * backend; commitDraft() must be called to persist.
   */
  async submitAddItemPanel(): Promise<void> {
    await this.addItemPanel.getByRole('button', { name: 'Save changes' }).click();
    // The panel stays mounted in the DOM after close; "closed" is signaled
    // by transform: translateX(100%) (slides off-screen) rather than
    // display/visibility, so waitFor({state: 'hidden'}) never fires.
    await this.page.waitForFunction(() => {
      const el = document.querySelector('[data-testid="dataset-item-panel"]') as HTMLElement | null;
      const t = el?.style.transform ?? '';
      return t.includes('translateX(100%)');
    });
    await this.draftBadge().waitFor({ state: 'visible' });
  }

  /**
   * Commit all staged draft changes as a new dataset version. Opens the
   * version-commit modal, optionally fills a version note, submits, and
   * waits for the Draft badge to clear.
   *
   * Prefers the testids added in this PR; falls back to role+name for
   * deployments without the new attributes.
   */
  async commitDraft(opts: { versionNote?: string } = {}): Promise<void> {
    await this.pageLevelCommitButton().click();
    const modal = await this.versionCommitModal();
    await modal.waitFor({ state: 'visible' });
    if (opts.versionNote !== undefined) {
      await modal.getByRole('textbox', { name: 'Version note (optional)' }).fill(opts.versionNote);
    }
    await modal.getByRole('button', { name: 'Save changes' }).click();
    await modal.waitFor({ state: 'hidden' });
    await this.draftBadge().waitFor({ state: 'hidden' });
  }

  /**
   * Discard all staged draft changes without committing.
   */
  async discardDraft(): Promise<void> {
    const byTestid = this.page.getByTestId('dataset-items-discard-button');
    const byRole = this.page.getByRole('button', { name: 'Discard changes' });
    const button = (await byTestid.count()) > 0 ? byTestid : byRole;
    await button.click();
    await this.draftBadge().waitFor({ state: 'hidden' });
  }

  private pageLevelCommitButton(): Locator {
    // The page header's "Save changes" button shares its accessible name
    // with the modal-level Save button (the panel-level button is hidden by
    // the time commitDraft() runs). Prefer the testid added in this PR;
    // fall back to "first visible 'Save changes' button" — the page-level
    // button is mounted first in the DOM, so .first() picks it.
    const byTestid = this.page.getByTestId('dataset-items-commit-button');
    return byTestid.or(this.page.getByRole('button', { name: 'Save changes' })).first();
  }

  private async versionCommitModal(): Promise<Locator> {
    const byTestid = this.page.getByTestId('dataset-version-commit-dialog');
    if ((await byTestid.count()) > 0) return byTestid;
    return this.page.getByRole('dialog', { name: 'Save changes' });
  }

  /**
   * Open the row's Actions menu at the given index, click Delete, and confirm
   * the "Remove suite items" dialog (shared component with Test Suites — the
   * title says "suite" even on the Datasets page). Stages a deletion as a
   * draft; commitDraft() must be called to persist.
   */
  async deleteItemByIndex(index: number): Promise<void> {
    const row = this.itemRow(index);
    await row.getByRole('button', { name: 'Actions menu' }).click();
    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    const dialog = this.page.getByRole('dialog', { name: 'Remove suite items' });
    await dialog.waitFor({ state: 'visible' });
    await dialog.getByRole('button', { name: 'Remove suite items' }).click();
    await dialog.waitFor({ state: 'hidden' });
    await this.draftBadge().waitFor({ state: 'visible' });
  }

  /**
   * Empty-state Add: when the dataset has zero items, the FE opens a modal
   * dialog "Add dataset item" with a JSON-editor textbox instead of the
   * sliding side-panel. The smoke test exercises this path when adding the
   * first item to a UI-created empty dataset.
   *
   * Submits the JSON immediately (no draft staging — the modal commits as
   * v2 directly). Resolves when the row appears in the items table.
   */
  async addItemViaEmptyStateModal(fields: Record<string, unknown>): Promise<void> {
    const triggers = this.page
      .getByRole('button', { name: 'Add new item' })
      .or(this.page.getByTestId('dataset-items-add-button'))
      .or(this.page.getByRole('button', { name: 'Add item' }));
    await triggers.first().click();
    const modal = this.page.getByRole('dialog', { name: 'Add dataset item' });
    await modal.waitFor({ state: 'visible' });
    // The textbox is a CodeMirror-style code editor with prefilled template.
    // Select-all + type replaces the content.
    const editor = modal.getByRole('textbox');
    await editor.click();
    await this.page.keyboard.press('ControlOrMeta+A');
    await this.page.keyboard.press('Delete');
    await this.page.keyboard.type(JSON.stringify(fields));
    await modal.getByRole('button', { name: 'Add dataset item' }).click();
    await modal.waitFor({ state: 'hidden' });
    // The modal commits directly (no draft); wait for the new row to render.
    await this.itemsTableBody.locator('tr[data-row-id]').first().waitFor({ state: 'visible' });
  }

  /** Add-item side-panel root. */
  get addItemPanel(): Locator {
    return this.page.getByTestId('dataset-item-panel');
  }

  /** Items table tbody — scopes row queries to the data rowgroup. */
  private get itemsTableBody(): Locator {
    return this.page.getByRole('tabpanel', { name: 'Items' }).locator('tbody');
  }
}
