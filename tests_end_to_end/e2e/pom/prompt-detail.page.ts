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
    // 2.0.61 has no `active-version-label` testid; the active version renders as
    // a badge span inside the header version selector (the bordered container).
    return this.page.locator('div.rounded-md.border span.comet-body-accented').first();
  }

  versionHistoryItem(label: string): Locator {
    // 2.0.61 has no `version-history-item-*` testid; the history timeline is a
    // plain list whose items carry the version label as text.
    return this.page.locator('li').filter({
      has: this.page.getByText(label, { exact: true }),
    });
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
}
