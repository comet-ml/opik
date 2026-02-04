import { Page, expect, Locator } from '@playwright/test';
import { BasePage } from './base.page';

export class PromptsPage extends BasePage {
  readonly searchInput: Locator;

  constructor(page: Page) {
    super(page, 'prompts');
    this.searchInput = page.getByTestId('search-input');
  }

  async goto(): Promise<void> {
    await super.goto();
  }

  async searchPrompt(name: string): Promise<void> {
    await this.searchInput.click();
    await this.searchInput.fill(name);
  }

  async clearSearch(): Promise<void> {
    await this.searchInput.fill('');
  }

  async checkPromptExists(name: string): Promise<void> {
    await this.searchPrompt(name);
    await expect(this.page.getByText(name).first()).toBeVisible();
    await this.clearSearch();
  }

  async checkPromptNotExists(name: string): Promise<void> {
    await this.searchPrompt(name);
    await expect(this.page.getByText(name)).not.toBeVisible();
    await this.clearSearch();
  }

  async clickPrompt(name: string): Promise<void> {
    await this.searchPrompt(name);
    // Wait for the search to filter the table
    await this.page.waitForTimeout(500);
    // Click on the "Versions" cell within the row to avoid the tooltip that appears on the name cell
    // Using nth(3) to get the 4th cell (0-indexed: checkbox, name, type, description, versions)
    const row = this.page.getByRole('row', { name: new RegExp(name) }).first();
    await row.getByRole('cell').nth(4).click();
    // Wait for navigation to prompt details page (UUID pattern)
    await this.page.waitForURL(/\/prompts\/[0-9a-f-]{36}/, { timeout: 30000 });
  }

  async deletePrompt(name: string): Promise<void> {
    await this.searchPrompt(name);
    await this.page
      .getByRole('row', { name })
      .first()
      .getByRole('button', { name: 'Actions menu' })
      .click();

    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Delete prompt' }).click();
    await this.clearSearch();
  }
}

export class PromptDetailsPage {
  readonly page: Page;
  readonly editButton: Locator;
  readonly promptTextarea: Locator;
  readonly saveButton: Locator;
  readonly commitsTab: Locator;

  constructor(page: Page) {
    this.page = page;
    this.editButton = page.getByRole('button', { name: 'Edit prompt' });
    this.promptTextarea = page.getByRole('textbox', { name: 'Prompt' });
    this.saveButton = page.getByRole('button', { name: 'Create new commit' });
    this.commitsTab = page.getByRole('tab', { name: 'Commits' });
  }

  async editPrompt(newPrompt: string): Promise<void> {
    await this.editButton.click();
    await this.promptTextarea.clear();
    await this.promptTextarea.fill(newPrompt);
    await this.saveButton.click();

    await this.page.waitForTimeout(1000);
  }

  async switchToCommitsTab(): Promise<void> {
    await this.commitsTab.click();
  }

  async getAllCommitVersions(): Promise<Record<string, string>> {
    await this.switchToCommitsTab();
    const commits: Record<string, string> = {};

    await this.page.waitForSelector('tbody tr', { timeout: 5000 });

    const commitRows = this.page.locator('tbody tr');
    const count = await commitRows.count();

    for (let i = 0; i < count; i++) {
      const row = commitRows.nth(i);
      const cells = row.locator('td');

      const commitIdCell = cells.nth(1);
      const commitId = await commitIdCell.textContent();

      const promptCell = cells.nth(2);
      const promptCode = promptCell.locator('code');
      const promptText = await promptCode.textContent();

      if (promptText && commitId) {
        commits[promptText.trim()] = commitId.trim();
      }
    }

    return commits;
  }

  async clickMostRecentCommit(): Promise<void> {
    await this.switchToCommitsTab();
    const firstCommit = this.page.locator('tbody tr').first();
    await firstCommit.click();
    await this.page.waitForTimeout(500);
  }

  async getSelectedCommitPrompt(): Promise<string> {
    // After clicking a commit, get the prompt from the Prompt tab
    const promptTab = this.page.getByRole('tab', { name: 'Prompt' });
    await promptTab.click();
    await this.page.waitForTimeout(500);

    const promptContainer = this.page.getByTestId('prompt-text-content');
    const text = await promptContainer.textContent();
    return text?.trim() || '';
  }
}
