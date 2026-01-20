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
    await this.page.getByRole('link', { name }).first().click();
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
