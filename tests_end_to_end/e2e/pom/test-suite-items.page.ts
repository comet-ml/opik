import { test } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { PlaygroundPage } from './playground.page';

/**
 * Per-suite items page. The underlying FE component is shared with DatasetItemsPage
 * (both routes resolve to the same DatasetDetailPage React component, discriminated
 * by URL prefix). Mechanics for items add/delete + draft staging are identical;
 * the surface difference is the "Run in" header dropdown and the
 * "<N> global assertions" pill.
 */
export class TestSuiteItemsPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
    private readonly suiteId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('Open test-suite items page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/test-suites/${this.suiteId}/items`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('Wait for Test cases tab ready', async () => {
      await this.page.getByRole('tab', { name: 'Test cases', selected: true }).waitFor({ state: 'visible' });
      const realRow = this.itemsTableBody.locator('tr[data-row-id]').first();
      const emptyState = this.page.getByText('No test cases yet');
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyState.waitFor({ state: 'visible' }),
      ]);
    });
  }

  /** Rendered row count on the current page. */
  async countItems(): Promise<number> {
    return test.step('Read test-suite item count', async () => {
      return this.itemsTableBody.locator('tr[data-row-id]').count();
    });
  }

  /** Read the global-assertions count from the header pill (test-suite-only pill). */
  async countGlobalAssertions(): Promise<number> {
    return test.step('Read global-assertions count', async () => {
      const pill = this.page.getByTestId('dataset-detail-global-assertions-pill');
      if ((await pill.count()) === 0) return 0;
      const value = await pill.first().getAttribute('data-count');
      return parseInt(value ?? '0', 10);
    });
  }

  /** Read the version label (e.g. "v1", "v2") from the header. */
  async readVersionLabel(): Promise<string> {
    return test.step('Read version label', async () => {
      const tag = this.page.getByTestId('dataset-detail-version-label');
      await tag.waitFor({ state: 'visible' });
      return (await tag.textContent())?.trim() ?? '';
    });
  }

  itemRow(index: number): Locator {
    return this.itemsTableBody.locator('tr[data-row-id]').nth(index);
  }

  itemRowById(id: string): Locator {
    return this.itemsTableBody.locator(`tr[data-row-id="${id}"]`);
  }

  draftBadge(): Locator {
    return this.page.getByText('Draft', { exact: true });
  }

  async clickAddItem(): Promise<void> {
    return test.step('Open add-item panel', async () => {
      await this.page.getByTestId('dataset-header-add-button').click();
      // Wait for the Data JSON editor inside the Add panel to be interactive.
      // This is more reliable than waitForPanelTransform on a multi-mount testid.
      await this.addItemPanel.locator('.cm-content').first().waitFor({ state: 'visible' });
    });
  }

  /**
   * Fill the test-suite-item Add panel. The panel has a single "Data" JSON
   * editor (CodeMirror), not per-column inputs like the dataset items panel.
   *
   * Pass the item's data as a Record; this method serializes it to JSON and
   * types it into the editor.
   */
  async fillAddItemPanel(data: Record<string, unknown>): Promise<void> {
    return test.step('Fill add-item panel', async () => {
      const editor = this.addItemPanel.locator('.cm-content').first();
      await editor.click();
      // Clear placeholder, then type the JSON object.
      await this.page.keyboard.press('ControlOrMeta+A');
      await this.page.keyboard.press('Delete');
      await this.page.keyboard.type(JSON.stringify(data, null, 2));
    });
  }

  /** Stages the new item as a draft; commitDraft() persists it as a new version. */
  async submitAddItemPanel(): Promise<void> {
    return test.step('Submit add-item panel (stage draft)', async () => {
      await this.addItemPanel.getByRole('button', { name: 'Save changes' }).click();
      // The panel slides out — its `Save changes` button becomes hidden when closed.
      await this.addItemPanel
        .getByRole('button', { name: 'Save changes' })
        .waitFor({ state: 'hidden' })
        .catch(() => {});
      await this.draftBadge().waitFor({ state: 'visible' });
    });
  }

  /** Commits all staged drafts as a new suite version via the confirmation modal. */
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

  /**
   * Open the suite in the Prompt Playground via the "Run in" → "Open in Playground" menu path.
   *
   * The confirm dialog "Load test suite into playground" only appears when the
   * playground already has unsaved state (per UseDatasetDropdown.tsx:60-67).
   * We handle both: dialog shown OR direct navigation.
   *
   * After loadPlayground fires, the URL gains a suite-id query param. Wait
   * for that signal before returning — bare `/playground` means the load
   * never happened.
   */
  async openInPlayground(): Promise<PlaygroundPage> {
    return test.step('Open suite in Playground', async () => {
      await this.page.getByTestId('dataset-header-run-in-trigger').click();
      await this.page.getByRole('menuitem', { name: /^Open in Playground/ }).click();

      const confirm = this.page.getByRole('dialog', { name: 'Load test suite into playground' });
      await Promise.race([
        confirm.waitFor({ state: 'visible', timeout: 5_000 }).then(async () => {
          await confirm.getByRole('button', { name: 'Load test suite' }).click();
        }),
        this.page.waitForURL((url) => url.pathname.endsWith('/playground'), { timeout: 5_000 }),
      ]).catch(() => {});

      // Wait for the playground to be on the right URL.
      await this.page.waitForURL((url) => url.pathname.endsWith('/playground'), { timeout: 15_000 });
      const playground = new PlaygroundPage(this.page, this.projectId);
      return playground;
    });
  }

  get addItemPanel(): Locator {
    // Two panels share the testid `test-suite-item-panel` (Add + Edit/Detail).
    // The visible one is whichever currently has translateX(0). We approximate
    // by looking at the panel that wraps the "Add item" heading or "Save changes" button.
    return this.page.getByTestId('test-suite-item-panel').filter({
      has: this.page.getByRole('button', { name: 'Save changes' }),
    });
  }

  private get itemsTableBody(): Locator {
    return this.page.getByRole('tabpanel', { name: 'Test cases' }).locator('tbody');
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
}
