import { Page, expect, Locator } from '@playwright/test';

export class TracesPage {
  readonly page: Page;
  readonly tracesTable: Locator;
  readonly nextPageButton: Locator;
  readonly deleteButton: Locator;
  readonly attachmentsSubmenuButton: Locator;
  readonly attachmentContainer: Locator;
  private nameColumnIndex: number = -1;

  constructor(page: Page) {
    this.page = page;
    this.tracesTable = page.getByRole('table');

    this.nextPageButton = page
      .locator('div')
      .filter({ hasText: /^Showing (\d+)-(\d+) of (\d+)/ })
      .nth(2)
      .locator('button:nth-of-type(3)');

    this.deleteButton = page
      .getByRole('button')
      .filter({ has: page.locator('svg path[d*="M3 6h18"]') });

    this.attachmentsSubmenuButton = page.getByRole('button', { name: 'Attachments' });
    this.attachmentContainer = page.getByLabel('Attachments');
  }

  async initialize(): Promise<void> {
    await this.page.waitForTimeout(1000);

    // Ensure we're on the Traces view (not Threads or Spans)
    const tracesToggle = this.page.getByRole('radio', { name: 'Traces' });
    if ((await tracesToggle.getAttribute('aria-checked')) !== 'true') {
      await tracesToggle.click();
      await this.page.waitForTimeout(500);
    }

    try {
      await this.page.getByTestId('columns-button').click({ timeout: 5000 });
    } catch {
      await this.page.reload();
      await this.page.getByTestId('columns-button').click({ timeout: 5000 });
    }

    try {
      await expect(
        this.page.getByRole('button', { name: 'Name' }).getByRole('checkbox')
      ).toBeChecked({ timeout: 2000 });
    } catch {
      await this.page.getByRole('button', { name: 'Name' }).click();
    }

    await this.page.keyboard.press('Escape');
  }

  private async resolveNameColumnIndex(): Promise<number> {
    if (this.nameColumnIndex > 0) return this.nameColumnIndex;

    const headers = this.page.locator('thead th');
    const count = await headers.count();
    for (let i = 0; i < count; i++) {
      const text = await headers.nth(i).innerText();
      if (text.match(/^Name\b/)) {
        this.nameColumnIndex = i + 1;
        return this.nameColumnIndex;
      }
    }
    throw new Error('Name column not found in traces table. Was initialize() called?');
  }

  async getAllTraceNamesOnPage(): Promise<string[]> {
    const idx = await this.resolveNameColumnIndex();
    const selector = `tr td:nth-child(${idx}) div span`;
    await this.page.waitForSelector(selector);
    return await this.page.locator(selector).allInnerTexts();
  }

  async clickFirstTraceWithName(traceName: string): Promise<void> {
    await this.page.getByRole('cell', { name: traceName, exact: true }).first().click();
  }

  async checkTraceAttachment(attachmentName?: string): Promise<void> {
    if (attachmentName) {
      await expect(this.attachmentsSubmenuButton).toBeVisible();
      await expect(this.attachmentContainer.filter({ hasText: attachmentName })).toBeVisible();
    } else {
      await expect(this.attachmentsSubmenuButton).toHaveCount(0);
    }
  }

  async getFirstTraceNameOnPage(): Promise<string | null> {
    const idx = await this.resolveNameColumnIndex();
    const selector = `tr td:nth-child(${idx}) div span`;
    await this.page.waitForSelector(selector);
    return await this.page.locator(selector).first().textContent();
  }

  async getAllTraceNamesInProject(): Promise<string[]> {
    const names: string[] = [];
    names.push(...(await this.getAllTraceNamesOnPage()));

    while (
      (await this.nextPageButton.isVisible()) &&
      (await this.nextPageButton.isEnabled())
    ) {
      await this.nextPageButton.click();
      await this.page.waitForTimeout(500);
      names.push(...(await this.getAllTraceNamesOnPage()));
    }

    return names;
  }

  getPaginationButton(): Locator {
    return this.page.getByRole('button', { name: 'Showing' });
  }

  async getNumberOfTracesOnPage(): Promise<number> {
    try {
      await expect(this.page.getByRole('row').first()).toBeVisible();
      return await this.page.getByRole('row').count();
    } catch {
      return 0;
    }
  }

  async getTotalNumberOfTracesInProject(): Promise<number> {
    const paginationButtonText = await this.getPaginationButton().innerText();
    const match = paginationButtonText.match(/of (\d+)/);
    return match ? parseInt(match[1]) : 0;
  }

  async waitForTracesToBeVisible(timeout: number = 10000, initialDelay: number = 1000): Promise<void> {
    const startTime = Date.now();
    let delay = initialDelay;

    while (Date.now() - startTime < timeout) {
      const tracesNumber = await this.getNumberOfTracesOnPage();
      if (tracesNumber > 0) {
        return;
      }

      await this.page.waitForTimeout(delay);
      delay = Math.min(delay * 2, timeout - (Date.now() - startTime));
      await this.page.reload();
    }

    throw new Error(`Could not get traces in UI within ${timeout}ms`);
  }

  async deleteSingleTraceByName(name: string): Promise<void> {
    const trace = this.page.getByRole('row').filter({ hasText: name }).first();
    await trace.getByLabel('Select row').click();
    await this.deleteButton.click();
    await this.page.getByRole('button', { name: 'Delete traces' }).click();
  }
}
