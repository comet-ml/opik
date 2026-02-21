import { Page, Locator, expect } from '@playwright/test';
import { dismissWelcomeWizardDialog } from '../helpers/welcome-wizard';

export class ThreadsPage {
  readonly page: Page;
  readonly searchInput: Locator;
  readonly logsTab: Locator;
  readonly threadsToggle: Locator;
  readonly threadRow: Locator;
  readonly threadContainer: Locator;
  readonly threadContainerDeleteButton: Locator;
  readonly threadContainerDeleteMenuItem: Locator;
  readonly threadTableDeleteButton: Locator;
  readonly outputContainer: Locator;

  constructor(page: Page) {
    this.page = page;
    this.searchInput = page.getByTestId('search-input');
    this.logsTab = page.getByRole('tab', { name: 'Logs' });
    this.threadsToggle = page.getByRole('radio', { name: 'Threads' });
    this.threadRow = page.locator('tbody tr');
    this.threadContainer = page.getByTestId('thread');
    this.threadContainerDeleteButton = this.threadContainer.getByRole('button', {
      name: 'Actions menu',
    });
    this.threadContainerDeleteMenuItem = page.getByRole('menuitem', { name: /delete/i });
    // Find the delete button by its trash icon SVG (same pattern as traces page)
    this.threadTableDeleteButton = page
      .getByRole('button')
      .filter({ has: page.locator('svg path[d*="M3 6h18"]') });
    this.outputContainer = page.getByTestId('thread').locator('.comet-markdown');
  }

  async switchToPage(): Promise<void> {
    await dismissWelcomeWizardDialog(this.page);

    await expect(this.logsTab).toBeVisible({ timeout: 15000 });
    if ((await this.logsTab.getAttribute('aria-selected')) !== 'true') {
      await this.logsTab.click();
    }

    // Then click the Threads toggle within the Logs tab
    if ((await this.threadsToggle.getAttribute('aria-checked')) !== 'true') {
      await this.threadsToggle.click();
      await expect(this.threadsToggle).toHaveAttribute('aria-checked', 'true');
    }
  }

  async getNumberOfThreadsOnPage(): Promise<number> {
    const maxWaitMs = 30000;
    const start = Date.now();

    try {
      while (Date.now() - start < maxWaitMs) {
        const count = await this.threadRow.count();
        if (count > 0) {
          await expect(this.threadRow.first()).toBeVisible();
          return count;
        }

        await this.page.waitForTimeout(1000);
      }

      throw new Error(`No threads visible after ${maxWaitMs}ms.`);
    } catch (error) {
      throw new Error(`No threads found in the project.\nError: ${error}`);
    }
  }

  async openThreadContent(threadId: string): Promise<void> {
    const threadRow = this.page
      .getByRole('row')
      .filter({ has: this.page.getByRole('cell', { name: threadId, exact: true }) })
      .first();

    await expect(threadRow).toBeVisible({ timeout: 15000 });
    await threadRow.scrollIntoViewIfNeeded();
    await threadRow.click({ timeout: 10000 });

    const openThreadUrl = new URL(this.page.url());
    openThreadUrl.searchParams.set("thread", threadId);
    await this.page.goto(openThreadUrl.toString());

    await this.page.waitForURL(
      (url) => new URL(url).searchParams.get("thread") === threadId,
      { timeout: 10000 },
    );
    await this.threadContainer.waitFor({ state: "visible" });
    await this.threadContainer.scrollIntoViewIfNeeded();
    await expect(this.threadContainer).toBeVisible();
    await expect(
      this.threadContainerDeleteButton,
    ).toBeVisible({ timeout: 10000 });
    await this.threadContainerDeleteButton.scrollIntoViewIfNeeded();
  }

  async checkMessageInThread(message: string, isOutput: boolean = false): Promise<void> {
    if (isOutput) {
      await expect(this.outputContainer.filter({ hasText: message })).toBeVisible();
    } else {
      await expect(this.threadContainer.getByText(message)).toBeVisible();
    }
  }

  async searchForThread(threadId: string): Promise<void> {
    await this.searchInput.fill("");
    await this.searchInput.fill(threadId);
    await expect(
      this.page
        .getByRole('row')
        .filter({ has: this.page.getByRole('cell', { name: threadId, exact: true }) })
    ).toHaveCount(1);
  }

  async clearThreadSearch(): Promise<void> {
    await this.searchInput.fill("");
    await expect(this.threadRow.first()).toBeVisible({ timeout: 10000 });
  }

  private getThreadRowById(threadId: string): Locator {
    return this.page.getByRole('row').filter({
      has: this.page.getByRole('cell', { name: threadId, exact: true }),
    });
  }

  async deleteThreadFromTable(threadId: string): Promise<void> {
    const row = this.getThreadRowById(threadId).first();
    await row.scrollIntoViewIfNeeded();
    const checkbox = row.getByRole('checkbox', { name: 'Select row' });
    await expect(checkbox).toBeVisible({ timeout: 5000 });
    await expect(row).toBeVisible({ timeout: 15000 });

    const isChecked = async () =>
      (await checkbox.getAttribute("data-state")) === "checked";

    for (let attempt = 0; attempt < 3; attempt++) {
      if (await isChecked()) {
        break;
      }

      await checkbox.click({ force: true });
      await this.page.waitForTimeout(250);

      if (await isChecked()) {
        break;
      }

      await checkbox.focus();
      await this.page.keyboard.press(" ");
      await this.page.waitForTimeout(250);

      if (await isChecked()) {
        break;
      }

      await row.click();
      await this.page.waitForTimeout(250);
    }

    await expect
      .poll(
        async () => {
          return isChecked();
        },
        { timeout: 10000 },
      )
      .toBe(true);
    await expect(this.threadTableDeleteButton).toBeEnabled({ timeout: 10000 });
    await this.threadTableDeleteButton.click();

    const confirmButton = this.page
      .getByRole('dialog')
      .getByRole('button', { name: 'Delete threads' });

    await expect(confirmButton).toBeVisible({
      timeout: 10000,
    });
    await confirmButton.click({
      delay: 100,
    });
    await expect(confirmButton).toBeHidden();
    await expect(
      this.page
        .getByRole('row')
        .filter({ has: this.page.getByRole('cell', { name: threadId, exact: true }) })
    ).toHaveCount(0, { timeout: 12000 });
  }

  async checkThreadIsDeleted(threadId: string): Promise<void> {
    await this.searchInput.fill(threadId);
    await expect(
      this.page
        .getByRole('row')
        .filter({ has: this.page.getByRole('cell', { name: threadId, exact: true }) })
    ).toHaveCount(0);
  }

  async deleteThreadFromThreadContentBar(): Promise<void> {
    await this.threadContainerDeleteButton.click({ timeout: 5000 });
    await this.threadContainerDeleteMenuItem.waitFor({ state: 'visible', timeout: 10000 });
    await expect(this.threadContainerDeleteMenuItem).toBeVisible({ timeout: 8000 });
    await this.threadContainerDeleteMenuItem.evaluate((menuItem) =>
      (menuItem as HTMLElement).click(),
    );
    await this.page.getByRole('button', { name: 'Delete thread' }).click();
  }

  async closeThreadContent(): Promise<void> {
    await this.page.keyboard.press('Escape');
  }
}
