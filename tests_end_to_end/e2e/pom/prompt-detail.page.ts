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
      const editor = firstMessageRow.locator('.cm-content').first();
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
