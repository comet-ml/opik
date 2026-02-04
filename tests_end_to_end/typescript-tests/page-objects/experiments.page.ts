import { Page, expect, Locator } from '@playwright/test';
import { BasePage } from './base.page';

export class ExperimentsPage extends BasePage {
  readonly searchInput: Locator;

  constructor(page: Page) {
    super(page, 'experiments');
    this.searchInput = page.getByTestId('search-input');
  }

  async goto(): Promise<void> {
    await super.goto();
  }

  async searchExperiment(name: string): Promise<void> {
    await this.searchInput.click();
    await this.searchInput.fill(name);
  }

  async clearSearch(): Promise<void> {
    await this.searchInput.fill('');
  }

  async checkExperimentExists(name: string): Promise<void> {
    await this.searchExperiment(name);
    await expect(this.page.getByText(name).first()).toBeVisible();
    await this.clearSearch();
  }

  async checkExperimentNotExists(name: string): Promise<void> {
    await this.searchExperiment(name);
    await expect(this.page.getByText(name)).not.toBeVisible();
    await this.clearSearch();
  }

  async clickExperiment(name: string): Promise<void> {
    await this.searchExperiment(name);
    // Wait for the search to filter the table
    await this.page.waitForTimeout(500);
    // Click on a different cell (e.g., Dataset or Project) to avoid tooltip interference on the name cell
    const row = this.page.getByRole('row', { name: new RegExp(name) }).first();
    await row.getByRole('cell').nth(2).click(); // Click on Dataset cell (index 2)
    // Wait for navigation to experiment details page (UUID pattern)
    await this.page.waitForURL(/\/experiments\/[0-9a-f-]{36}/, { timeout: 30000 });
  }

  async deleteExperiment(name: string): Promise<void> {
    await this.searchExperiment(name);
    await this.page
      .getByRole('row', { name })
      .first()
      .getByRole('button', { name: 'Actions menu' })
      .click();

    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Delete experiment' }).click();
    await this.clearSearch();
  }
}
