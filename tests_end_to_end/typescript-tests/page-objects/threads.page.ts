import { Page, Locator, expect } from '@playwright/test';

export class ThreadsPage {
  readonly page: Page;
  readonly searchInput: Locator;
  readonly threadsTab: Locator;
  readonly threadRow: Locator;
  readonly threadContainer: Locator;
  readonly threadContainerDeleteButton: Locator;
  readonly threadDeletePopupDeleteButton: Locator;
  readonly threadCheckbox: Locator;
  readonly threadTableDeleteButton: Locator;
  readonly outputContainer: Locator;

  constructor(page: Page) {
    this.page = page;
    this.searchInput = page.getByTestId('search-input');
    this.threadsTab = page.getByRole('tab', { name: 'Threads' });
    this.threadRow = page.locator('tbody tr');
    this.threadContainer = page.getByTestId('thread');
    this.threadContainerDeleteButton = page
      .locator('div')
      .filter({ hasText: /^Add to/ })
      .getByRole('button')
      .nth(2);
    this.threadDeletePopupDeleteButton = page.getByRole('button', { name: 'Delete' });
    this.threadCheckbox = page.getByLabel('Select row');
    // Find the delete button by its trash icon SVG (same pattern as traces page)
    this.threadTableDeleteButton = page
      .getByRole('button')
      .filter({ has: page.locator('svg path[d*="M3 6h18"]') });
    this.outputContainer = page.getByTestId('thread').locator('.comet-markdown');
  }

  async switchToPage(): Promise<void> {
    await this.threadsTab.click();
  }

  async getNumberOfThreadsOnPage(): Promise<number> {
    try {
      // Retry up to 5 times if no threads found initially
      for (let i = 0; i < 5; i++) {
        const count = await this.threadRow.count();
        if (count === 0) {
          await this.page.waitForTimeout(1000);
          await this.page.reload();
        } else {
          break;
        }
      }

      await expect(this.threadRow.first()).toBeVisible();
      return await this.threadRow.count();
    } catch (error) {
      throw new Error(`No threads found in the project.\nError: ${error}`);
    }
  }

  async openThreadContent(threadId: string): Promise<void> {
    await this.page.getByRole('button', { name: threadId }).click();
  }

  async checkMessageInThread(message: string, isOutput: boolean = false): Promise<void> {
    if (isOutput) {
      await expect(this.outputContainer.filter({ hasText: message })).toBeVisible();
    } else {
      await expect(this.threadContainer.getByText(message)).toBeVisible();
    }
  }

  async searchForThread(threadId: string): Promise<void> {
    await this.searchInput.fill(threadId);
    await expect(this.threadRow).toHaveCount(1);
  }

  async deleteThreadFromTable(): Promise<void> {
    await this.threadCheckbox.click();
    await this.threadTableDeleteButton.click();
    await this.page.waitForTimeout(500);
    await this.threadDeletePopupDeleteButton.click();
  }

  async checkThreadIsDeleted(threadId: string): Promise<void> {
    await this.searchInput.fill(threadId);
    await expect(this.threadRow.filter({ hasText: threadId })).toHaveCount(0);
  }

  async deleteThreadFromThreadContentBar(): Promise<void> {
    await this.threadContainerDeleteButton.click();
    await this.threadDeletePopupDeleteButton.click();
  }

  async closeThreadContent(): Promise<void> {
    await this.page.keyboard.press('Escape');
  }
}
