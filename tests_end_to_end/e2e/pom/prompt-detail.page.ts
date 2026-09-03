import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';

export class PromptDetailPage {
  constructor(private readonly page: Page) {}

  async waitForReady(): Promise<void> {
    return test.step('wait for prompt detail to load', async () => {
      // Edit button renders only after the loading skeleton is replaced with real content
      await this.page.getByRole('button', { name: 'Edit' }).waitFor({ state: 'visible' });
    });
  }

  promptNameHeading(): Locator {
    return this.page.getByRole('heading', { level: 1 });
  }

  textContent(): Locator {
    return this.page.getByTestId('prompt-text-content');
  }

  chatMessages(): Locator {
    return this.page.getByTestId('prompt-chat-messages');
  }

  activeVersionLabel(): Locator {
    return this.page.getByTestId('active-version-label');
  }

  versionHistoryItem(label: string): Locator {
    return this.page.getByTestId(`version-history-item-${label}`);
  }

  /**
   * Every rendered version row. Prefix-matched on the same testid
   * `versionHistoryItem` addresses by name, because the point of the callers is
   * to find out *which* versions are on the page — they can't name them first.
   */
  versionHistoryItems(): Locator {
    return this.page.locator('[data-testid^="version-history-item-"]');
  }

  /** The version labels in the order the sidebar renders them, newest first. */
  async versionHistoryLabels(): Promise<string[]> {
    return test.step('read version history labels', async () => {
      return this.versionHistoryItems().evaluateAll((rows) =>
        rows.map((row) => (row.getAttribute('data-testid') ?? '').replace('version-history-item-', '')),
      );
    });
  }

  /**
   * Page the sidebar to the end by scrolling its last row into view, which is
   * what puts the list's load-more sentinel on screen. Polls the row count
   * rather than waiting a fixed time: each page arrives when it arrives.
   */
  async loadAllVersions(expectedCount: number): Promise<void> {
    return test.step(`scroll version history until all ${expectedCount} versions are loaded`, async () => {
      const items = this.versionHistoryItems();
      await expect
        .poll(
          async () => {
            const count = await items.count();
            if (count < expectedCount) {
              await items.last().scrollIntoViewIfNeeded();
            }
            return items.count();
          },
          {
            timeout: 30_000,
            message: `version history stopped loading before reaching ${expectedCount} versions`,
          },
        )
        .toBe(expectedCount);
    });
  }

  /**
   * The dropdown a menu trigger just opened. Radix keeps at most one open, so
   * this is unambiguous — the callers below assert on the menu they opened.
   */
  private openMenu(): Locator {
    return this.page.getByRole('menu');
  }

  /** Open "Diff" — the compare-against-version picker. */
  async openDiffMenu(): Promise<void> {
    return test.step('open the Diff menu', async () => {
      await this.page.getByRole('button', { name: 'Diff', exact: true }).click();
      await this.openMenu().waitFor({ state: 'visible' });
    });
  }

  /** The version rows the open Diff menu offers. */
  diffMenuVersionItems(): Locator {
    return this.openMenu().getByRole('menuitem');
  }

  /** The version labels the open Diff menu offers, in render order. */
  async diffMenuVersionLabels(): Promise<string[]> {
    return test.step('read the Diff menu version labels', async () => {
      // Each item reads "v29\n< 1 min ago" — the label is its first line.
      const texts = await this.diffMenuVersionItems().allInnerTexts();
      return texts.map((t) => t.split('\n')[0].trim());
    });
  }

  /** Pick a version from the open Diff menu, opening the compare sheet. */
  async compareAgainstVersion(label: string): Promise<void> {
    return test.step(`compare against version "${label}"`, async () => {
      const item = this.openMenu()
        .getByRole('menuitem')
        .filter({ has: this.page.getByText(label, { exact: true }) });
      // Anchored on an exact-text child rather than `hasText`, which would also
      // match v10..v19 when asked for v1, and asserted to resolve to exactly
      // one row rather than taking .first().
      await expect(item).toHaveCount(1);
      await item.click();
      await this.compareSheet().waitFor({ state: 'visible' });
    });
  }

  /** The side sheet the Diff menu opens. */
  compareSheet(): Locator {
    return this.page.getByRole('dialog');
  }

  compareSheetHeading(): Locator {
    return this.compareSheet().getByRole('heading');
  }

  /**
   * Every distinct `vN` label rendered anywhere inside the compare sheet.
   *
   * The regression this exists for is a sheet that labels its panes from the
   * count of *loaded* versions rather than from each version's own number — so
   * a spec has to be able to say "no other version label appears here", not
   * just "the two I expect are present".
   */
  async compareSheetVersionLabels(): Promise<string[]> {
    return test.step('read every version label rendered in the compare sheet', async () => {
      const text = await this.compareSheet().innerText();
      return [...new Set(text.match(/\bv\d+\b/g) ?? [])].sort();
    });
  }

  /** Open "Deploy to" — the environment picker for the active version. */
  async openDeployMenu(): Promise<void> {
    return test.step('open the Deploy to menu', async () => {
      await this.page.getByRole('button', { name: 'Deploy to' }).click();
      await this.openMenu().waitFor({ state: 'visible' });
    });
  }

  /** The open Deploy menu's row for one environment. */
  deployMenuEnvironmentRow(environment: string): Locator {
    return this.openMenu()
      .getByRole('menuitem')
      .filter({ has: this.page.getByText(environment, { exact: true }) });
  }

  /**
   * Every "Currently vN" note in the open Deploy menu — one per environment
   * that some *other* version of this prompt is deployed to.
   */
  deployMenuCurrentlyLabels(): Locator {
    return this.openMenu().getByText(/^Currently /);
  }

  async editTextPrompt(newTemplate: string): Promise<void> {
    return test.step(`edit text prompt template`, async () => {
      await this.page.getByRole('button', { name: 'Edit' }).click();
      const sheet = this.page.getByRole('dialog');
      await sheet.waitFor({ state: 'visible' });
      const editor = sheet.getByPlaceholder('Type your prompt...');
      await editor.fill(newTemplate);
      await sheet.getByRole('button', { name: 'Create new version' }).click();
      await sheet.waitFor({ state: 'hidden' });
    });
  }

  async editChatFirstMessage(newContent: string): Promise<void> {
    return test.step(`edit first chat message`, async () => {
      await this.page.getByRole('button', { name: 'Edit' }).click();
      const sheet = this.page.getByRole('dialog');
      await sheet.waitFor({ state: 'visible' });
      const firstMessageRow = sheet.getByTestId('playground-message-row').first();
      const editor = firstMessageRow.getByTestId('playground-message-editor').locator('.cm-content').first();
      await editor.click();
      await editor.press('ControlOrMeta+a');
      await editor.pressSequentially(newContent);
      await sheet.getByRole('button', { name: 'Create new version' }).click();
      await sheet.waitFor({ state: 'hidden' });
    });
  }

  async selectVersion(label: string): Promise<void> {
    return test.step(`select version "${label}" from history timeline`, async () => {
      const item = this.versionHistoryItem(label);
      await item.waitFor({ state: 'visible' });
      await item.click();
      await expect(this.activeVersionLabel()).toHaveText(label);
    });
  }

  /** Open the "Use" dropdown and click "Load in Prompt playground", then wait for the Playground URL.
   * A confirmation dialog may appear if the playground is not empty — handle it if present. */
  async loadInPlayground(): Promise<void> {
    return test.step('load prompt into Playground', async () => {
      await this.page.getByRole('button', { name: 'Use' }).click();
      await this.page.getByRole('menuitem', { name: 'Load in Prompt playground' }).click();
      // A confirmation dialog appears only when the playground already has content.
      // If it shows up within a short window, click through it; otherwise proceed.
      const dialog = this.page.getByRole('dialog', { name: 'Load prompt' });
      const confirmBtn = dialog.getByRole('button', { name: 'Load prompt' });
      const appeared = await confirmBtn.isVisible().catch(() => false);
      if (!appeared) {
        await confirmBtn.waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});
      }
      if (await confirmBtn.isVisible().catch(() => false)) {
        await confirmBtn.click();
      }
      await this.page.waitForURL((url) => url.pathname.includes('/playground'));
    });
  }
}
