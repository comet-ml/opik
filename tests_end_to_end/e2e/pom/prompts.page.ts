import { test } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { PromptDetailPage } from './prompt-detail.page';

export class PromptsPage {
  private projectId: string | null = null;

  constructor(private readonly page: Page) {}

  async createTextPromptViaUI(name: string, template: string): Promise<PromptDetailPage> {
    return test.step(`create text prompt "${name}" via UI`, async () => {
      const emptyStateBtn = this.page.getByRole('button', { name: 'Create a text prompt' });
      if (await emptyStateBtn.isVisible()) {
        await emptyStateBtn.click();
      } else {
        await this.page.getByRole('button', { name: 'Prompt' }).click();
        await this.page.getByRole('menuitem', { name: 'Text prompt' }).click();
      }
      await this.page.getByLabel('Name').fill(name);
      const promptTextarea = this.page.getByPlaceholder('Type your prompt...');
      await promptTextarea.click();
      await promptTextarea.fill(template);
      await this.page.getByRole('button', { name: 'Create prompt' }).click();
      await this.page.waitForURL((url) => {
        const parts = url.pathname.split('/').filter(Boolean);
        const promptsIdx = parts.lastIndexOf('prompts');
        return promptsIdx >= 0 && promptsIdx < parts.length - 1;
      });
      return new PromptDetailPage(this.page);
    });
  }

  async createChatPromptViaUI(name: string, messageContent: string): Promise<PromptDetailPage> {
    return test.step(`create chat prompt "${name}" via UI`, async () => {
      const emptyStateBtn = this.page.getByRole('button', { name: 'Create a chat prompt' });
      if (await emptyStateBtn.isVisible()) {
        await emptyStateBtn.click();
      } else {
        await this.page.getByRole('button', { name: 'Prompt' }).click();
        await this.page.getByRole('menuitem', { name: 'Chat prompt' }).click();
      }
      await this.page.getByLabel('Name').fill(name);
      const firstMessageRow = this.page.getByTestId('playground-message-row').first();
      const messageEditor = firstMessageRow.getByTestId('playground-message-editor').locator('.cm-content').first();
      await messageEditor.click();
      await messageEditor.fill(messageContent);
      await this.page.getByRole('button', { name: 'Create prompt' }).click();
      await this.page.waitForURL((url) => {
        const parts = url.pathname.split('/').filter(Boolean);
        const promptsIdx = parts.lastIndexOf('prompts');
        return promptsIdx >= 0 && promptsIdx < parts.length - 1;
      });
      return new PromptDetailPage(this.page);
    });
  }

  async goto(projectId: string): Promise<void> {
    return test.step('navigate to Prompts Library', async () => {
      this.projectId = projectId;
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/prompts/`);
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('wait for Prompts Library to load', async () => {
      const realRow = this.page.locator('tbody tr[data-row-id]').first();
      const emptyHeading = this.page.getByRole('heading', { name: 'No prompts yet', level: 2 });
      await Promise.race([
        realRow.waitFor({ state: 'visible' }),
        emptyHeading.waitFor({ state: 'visible' }),
      ]);
    });
  }

  promptRow(name: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  async openPromptByName(name: string): Promise<PromptDetailPage> {
    return test.step(`open prompt "${name}"`, async () => {
      if (!this.projectId) {
        throw new Error('PromptsPage.openPromptByName: call goto(projectId) first');
      }
      const row = this.promptRow(name);
      await row.waitFor({ state: 'visible' });
      const promptId = await row.getAttribute('data-row-id');
      if (!promptId) {
        throw new Error(`PromptsPage.openPromptByName: row for "${name}" has no data-row-id`);
      }
      await row.getByRole('cell', { name, exact: true }).click();
      await this.page.waitForURL((url) => url.pathname.includes(`/prompts/${promptId}`));
      return new PromptDetailPage(this.page);
    });
  }
}
