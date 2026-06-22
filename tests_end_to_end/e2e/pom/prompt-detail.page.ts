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
