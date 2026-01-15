/**
 * Traces Page Spans Menu Page Object
 * Handles interactions with the spans menu in trace details
 */

import { Page, Locator, expect } from '@playwright/test';

export class TracesPageSpansMenu {
  readonly page: Page;
  readonly inputOutputTab: string;
  readonly feedbackScoresTab: string;
  readonly metadataTab: string;
  readonly spanTitle: Locator;
  readonly attachmentsSubmenuButton: Locator;
  readonly attachmentContainer: Locator;

  constructor(page: Page) {
    this.page = page;
    this.inputOutputTab = 'Input/Output';
    this.feedbackScoresTab = 'Feedback scores';
    this.metadataTab = 'Metadata';
    this.spanTitle = page.getByTestId('data-viewer-title');
    this.attachmentsSubmenuButton = page.getByRole('button', { name: 'Attachments' });
    this.attachmentContainer = page.getByLabel('Attachments');
  }

  getFirstTraceByName(name: string): Locator {
    return this.page.getByRole('button', { name }).first();
  }

  getFirstSpanByName(name: string): Locator {
    return this.page.getByText(name).first();
  }

  async checkSpanExistsByName(name: string): Promise<void> {
    await expect(this.page.getByText(name).first()).toBeVisible();
  }

  async checkTagExistsByName(tagName: string): Promise<void> {
    await expect(this.page.getByText(tagName)).toBeVisible();
  }

  getInputOutputTab(): Locator {
    return this.page.getByRole('tab', { name: this.inputOutputTab });
  }

  getFeedbackScoresTab(): Locator {
    return this.page.getByRole('tab', { name: this.feedbackScoresTab });
  }

  getMetadataTab(): Locator {
    return this.page.getByRole('tab', { name: 'Metadata' });
  }

  async openSpanContent(spanName: string): Promise<void> {
    await this.page.getByText(spanName).first().click();
    await expect(this.spanTitle.filter({ hasText: spanName })).toBeVisible();
  }

  async checkSpanAttachment(attachmentName: string): Promise<void> {
    await expect(this.attachmentsSubmenuButton).toBeVisible();
    await expect(this.attachmentContainer.filter({ hasText: attachmentName })).toBeVisible();
  }
}
