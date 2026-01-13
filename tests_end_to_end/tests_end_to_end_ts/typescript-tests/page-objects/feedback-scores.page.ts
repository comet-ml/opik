import { Page, expect, Locator } from '@playwright/test';
import { BasePage } from './base.page';

export class FeedbackScoresPage extends BasePage {
  readonly searchInput: Locator;
  readonly createButton: Locator;
  readonly feedbackNameInput: Locator;
  readonly typeCombobox: Locator;
  readonly createDefinitionButton: Locator;

  constructor(page: Page) {
    super(page, 'configuration', '?tab=feedback-definitions');
    this.searchInput = page.getByTestId('search-input');
    this.createButton = page.getByRole('button', { name: 'Create new feedback definition' });
    this.feedbackNameInput = page.getByPlaceholder('Feedback definition name');
    this.typeCombobox = page.getByRole('combobox');
    this.createDefinitionButton = page.getByRole('button', { name: 'Create feedback definition' });
  }

  async goto(): Promise<void> {
    await super.goto();
  }

  async searchFeedback(name: string): Promise<void> {
    await this.searchInput.click();
    await this.searchInput.fill(name);
  }

  async clearSearch(): Promise<void> {
    await this.searchInput.fill('');
  }

  async checkFeedbackExists(name: string): Promise<void> {
    await this.searchFeedback(name);
    await expect(this.page.getByText(name).first()).toBeVisible();
    await this.clearSearch();
  }

  async checkFeedbackNotExists(name: string): Promise<void> {
    await this.searchFeedback(name);
    await expect(this.page.getByText(name).first()).not.toBeVisible();
    await this.clearSearch();
  }

  async fillCategoricalValues(categories: Record<string, number>): Promise<void> {
    const keys = Object.keys(categories);

    for (let i = 0; i < keys.length; i++) {
      const key = keys[i];
      const nameInput = this.page.getByPlaceholder('Name').nth(i + 2);
      const valueInput = this.page.getByPlaceholder('0.0').nth(i);

      await nameInput.click();
      await nameInput.fill(key);
      await valueInput.click();
      await valueInput.fill(String(categories[key]));

      if (i < keys.length - 1) {
        await this.page.getByRole('button', { name: 'Add category' }).click();
      }
    }
  }

  async fillNumericalValues(min: number, max: number): Promise<void> {
    const minInput = this.page.getByPlaceholder('Min');
    const maxInput = this.page.getByPlaceholder('Max');

    await minInput.click();
    await minInput.fill(String(min));
    await maxInput.click();
    await maxInput.fill(String(max));
  }

  async createFeedbackDefinition(
    name: string,
    type: 'categorical' | 'numerical',
    options?: { categories?: Record<string, number>; min?: number; max?: number }
  ): Promise<void> {
    await this.createButton.first().click();
    await this.feedbackNameInput.fill(name);
    await this.typeCombobox.click();
    await this.page.getByLabel(type === 'categorical' ? 'Categorical' : 'Numerical').click();

    if (type === 'categorical' && options?.categories) {
      await this.fillCategoricalValues(options.categories);
    } else if (type === 'numerical' && options?.min !== undefined && options?.max !== undefined) {
      await this.fillNumericalValues(options.min, options.max);
    }

    await this.createDefinitionButton.click();
  }

  async editFeedbackDefinition(
    name: string,
    newName: string,
    options?: { categories?: Record<string, number>; min?: number; max?: number }
  ): Promise<void> {
    await this.searchFeedback(name);
    await expect(this.page.getByRole('row', { name }).first()).toBeVisible();

    await this.page
      .getByRole('row', { name })
      .first()
      .getByRole('button', { name: 'Actions menu' })
      .click();

    await this.page.getByRole('menuitem', { name: 'Edit' }).click();
    await this.feedbackNameInput.clear();
    await this.feedbackNameInput.fill(newName);

    if (options?.categories) {
      await this.page.getByPlaceholder('Name').nth(2).clear();
      await this.page.getByPlaceholder('0.0').first().clear();
      await this.fillCategoricalValues(options.categories);
    } else if (options?.min !== undefined && options?.max !== undefined) {
      await this.page.getByPlaceholder('Min').clear();
      await this.page.getByPlaceholder('Max').clear();
      await this.fillNumericalValues(options.min, options.max);
    }

    await this.page.getByRole('button', { name: 'Update feedback definition' }).click();
    await this.clearSearch();
  }

  async deleteFeedbackDefinition(name: string): Promise<void> {
    await this.searchFeedback(name);
    await expect(this.page.getByRole('row', { name }).first()).toBeVisible();

    await this.page
      .getByRole('row', { name })
      .first()
      .getByRole('button', { name: 'Actions menu' })
      .click();

    await this.page.getByRole('menuitem', { name: 'Delete' }).click();
    await this.page.getByRole('button', { name: 'Delete feedback definition' }).click();
    await this.clearSearch();
  }

  async getFeedbackType(name: string): Promise<string> {
    await this.searchFeedback(name);
    const row = this.page.getByRole('row').filter({ hasText: name }).first();
    const cells = row.locator('td');
    const typeCell = cells.nth(2);
    const type = await typeCell.textContent();
    await this.clearSearch();
    return type?.trim() || '';
  }

  async getFeedbackValues(name: string): Promise<string> {
    await this.searchFeedback(name);
    const row = this.page.getByRole('row').filter({ hasText: name }).first();
    const cells = row.locator('td');
    const valuesCell = cells.nth(3);
    const values = await valuesCell.textContent();
    await this.clearSearch();
    return values?.trim() || '';
  }
}
