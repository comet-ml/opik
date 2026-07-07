import { test } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

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

  itemRow(index: number): Locator {
    return this.itemsTableBody.locator('tr[data-row-id]').nth(index);
  }

  itemRowById(id: string): Locator {
    return this.itemsTableBody.locator(`tr[data-row-id="${id}"]`);
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
      await this.editItemPanel.getByRole('region').first().waitFor({ state: 'visible' });
    });
  }

  /**
   * Edits a single field in the open editor. The editor autosaves: changing a
   * field stages an "edited" draft (Draft badge appears), it does not persist
   * on its own — commitDraft() turns the draft into a new version.
   */
  async editItemField(field: string, value: string): Promise<void> {
    return test.step(`Edit field "${field}"`, async () => {
      await this.editItemPanel
        .getByRole('region', { name: field })
        .getByPlaceholder('Enter text for this field…')
        .fill(value);
      await this.draftBadge().waitFor({ state: 'visible' });
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

  async fillAddItemPanel(fields: Record<string, string>): Promise<void> {
    return test.step('Fill add-item panel', async () => {
      for (const [column, value] of Object.entries(fields)) {
        await this.addItemPanel
          .getByRole('region', { name: column })
          .getByPlaceholder('Enter text for this field…')
          .fill(value);
      }
    });
  }

  /** Stages the new item as a draft; commitDraft() persists it. */
  async submitAddItemPanel(): Promise<void> {
    return test.step('Submit add-item panel (stage draft)', async () => {
      await this.addItemPanel.getByRole('button', { name: 'Save changes' }).click();
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
   * Empty-state Add takes a different FE path: opens a JSON-editor modal that
   * commits directly (no draft staging) instead of the sliding side-panel.
   */
  async addItemViaEmptyStateModal(fields: Record<string, unknown>): Promise<void> {
    return test.step('Add item via empty-state modal', async () => {
      const triggers = this.page
        .getByTestId('dataset-header-add-button')
        .or(this.page.getByTestId('dataset-items-empty-add-button'));
      await triggers.first().click();
      const modal = this.page.getByRole('dialog', { name: 'Add record' });
      await modal.waitFor({ state: 'visible' });
      const editor = modal.getByRole('textbox');
      await editor.click();
      await this.page.keyboard.press('ControlOrMeta+A');
      await this.page.keyboard.press('Delete');
      await this.page.keyboard.type(JSON.stringify(fields));
      await modal.getByRole('button', { name: 'Add record' }).click();
      await modal.waitFor({ state: 'hidden' });
      await this.itemsTableBody.locator('tr[data-row-id]').first().waitFor({ state: 'visible' });
    });
  }

  get addItemPanel(): Locator {
    return this.page.getByTestId('dataset-item-panel');
  }

  get editItemPanel(): Locator {
    return this.page.getByTestId('dataset-item-editor');
  }

  private get itemsTableBody(): Locator {
    return this.page.getByRole('tabpanel', { name: 'Records' }).locator('tbody');
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
