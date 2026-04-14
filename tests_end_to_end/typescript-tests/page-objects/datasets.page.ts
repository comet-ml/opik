import { Page, expect } from '@playwright/test';
import { BasePage } from './base.page';

export class DatasetsPage extends BasePage {
  constructor(page: Page) {
    super(page, 'test-suites');
  }

  async createDatasetByName(datasetName: string): Promise<void> {
    await this.page.getByRole('button', { name: 'Create new' }).first().click();
    await this.page.getByRole('dialog').getByPlaceholder('Name').fill(datasetName);
    await this.page.getByRole('dialog').getByRole('button', { name: 'Create new' }).click();
  }

  async selectDatasetByName(name: string): Promise<void> {
    await this.page.getByRole('cell', { name, exact: true }).click();
    // Wait for navigation to dataset items page
    await this.page.waitForURL(/\/items/);
  }

  async searchDataset(datasetName: string): Promise<void> {
    await this.page.getByTestId('search-input').click();
    await this.page.getByTestId('search-input').fill(datasetName);
  }

  async checkDatasetExists(datasetName: string): Promise<void> {
    await expect(this.page.getByText(datasetName).first()).toBeVisible();
  }

  async checkDatasetNotExists(datasetName: string): Promise<void> {
    await expect(this.page.getByText(datasetName).first()).not.toBeVisible();
  }

  async deleteDatasetByName(datasetName: string): Promise<void> {
    await this.searchDataset(datasetName);

    const row = this.page
      .getByRole('row')
      .filter({ hasText: datasetName })
      .filter({ has: this.page.getByRole('cell', { name: datasetName, exact: true }) });

    await row.getByRole('button').click();
    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Delete dataset' }).click();
  }
}
