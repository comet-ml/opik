import { Page, Locator, expect } from '@playwright/test';

export class RulesPage {
  private static readonly DEFAULT_CLICK_TIMEOUT = 2000;

  readonly page: Page;
  readonly onlineEvaluationTab: Locator;
  readonly createRuleButton: Locator;
  readonly ruleNameInput: Locator;
  readonly modelCombobox: Locator;
  readonly templateCombobox: Locator;
  readonly variableMapInput: Locator;
  readonly createRuleSubmitButton: Locator;
  readonly filteringButton: Locator;
  readonly addFilterButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.onlineEvaluationTab = page.getByRole('tab', { name: 'Online evaluation' });
    this.createRuleButton = page.getByRole('button', { name: /Create.*rule/i });
    this.ruleNameInput = page.getByPlaceholder('Rule name');
    this.modelCombobox = page.getByRole('combobox').filter({ hasText: 'Select an LLM model' });
    this.templateCombobox = page.getByRole('combobox').filter({ hasText: 'Custom LLM-as-judge' });
    this.variableMapInput = page.getByPlaceholder('Select a key from recent trace');
    this.createRuleSubmitButton = page.getByRole('button', { name: 'Create rule' });
    this.filteringButton = page.getByRole('button', { name: 'Filtering' });
    this.addFilterButton = page.getByRole('button', { name: 'Add filter' });
  }

  async navigateToRulesTab(): Promise<void> {
    console.log('Navigating to online evaluation rules tab');

    try {
      await expect(this.onlineEvaluationTab).toBeVisible({ timeout: 5000 });
    } catch (error) {
      throw new Error(`Rules tab not found, possible error loading: ${error}`);
    }

    await this.onlineEvaluationTab.click();
    console.log('Successfully navigated to rules tab');
  }

  async selectModel(providerDisplayName: string, modelUiSelector: string): Promise<void> {
    console.log(`Selecting model: ${providerDisplayName} -> ${modelUiSelector}`);

    await this.modelCombobox.click();

    const providerElement = this.page.getByText(providerDisplayName, { exact: true });
    await providerElement.hover();

    try {
      const options = await this.page.getByRole('option').all();
      let targetOption = null;

      for (const option of options) {
        const textContent = (await option.innerText()).trim();
        if (textContent === modelUiSelector) {
          targetOption = option;
          break;
        }
      }

      if (targetOption) {
        await targetOption.click();
      } else {
        await this.page.getByRole('option').filter({ hasText: modelUiSelector }).first().click();
      }
    } catch (error) {
      console.warn(`Failed to select model with exact matching, trying fallback: ${error}`);
      await this.page.getByRole('option').filter({ hasText: modelUiSelector }).first().click();
    }

    console.log(`Successfully selected ${providerDisplayName} -> ${modelUiSelector}`);
  }

  async createModerationRule(
    ruleName: string,
    providerDisplayName: string,
    modelUiSelector: string
  ): Promise<void> {
    console.log(`Creating new moderation rule: ${ruleName}`);

    // Click create rule button
    await this.createRuleButton.click();

    // Fill rule details
    await this.ruleNameInput.fill(ruleName);

    // Select model
    await this.selectModel(providerDisplayName, modelUiSelector);

    // Select moderation template
    await this.templateCombobox.click();
    await this.page.getByLabel('Moderation', { exact: true }).click();

    // Fill in variable mapping
    await this.variableMapInput.click();
    await this.variableMapInput.fill('output.output');

    // Try to click the option if it exists, otherwise just proceed (for projects with no traces)
    try {
      await this.page.getByRole('option', { name: 'output.output' }).click({ timeout: RulesPage.DEFAULT_CLICK_TIMEOUT });
    } catch (error) {
      console.log('No output.output option found (no recent traces), proceeding with manual entry');
      await this.page.keyboard.press('Escape'); // Close any open dropdown
    }

    // Create rule
    await this.createRuleSubmitButton.click();

    console.log(`Successfully created moderation rule: ${ruleName}`);
  }

  async createModerationRuleWithFilters(
    ruleName: string,
    providerDisplayName: string,
    modelUiSelector: string,
    filters: Array<{ field: string; operator: string; value: string }>
  ): Promise<void> {
    console.log(`Creating new moderation rule with filters: ${ruleName}`);

    // Click create rule button
    await this.createRuleButton.click();

    // Fill rule details
    await this.ruleNameInput.fill(ruleName);

    // Select model
    await this.selectModel(providerDisplayName, modelUiSelector);

    // Select moderation template
    await this.templateCombobox.click();
    await this.page.getByLabel('Moderation', { exact: true }).click();

    // Fill in variable mapping
    await this.variableMapInput.click();
    await this.variableMapInput.fill('output.output');
    await this.page.getByRole('option', { name: 'output.output' }).click();

    // Add filters if provided
    if (filters && filters.length > 0) {
      console.log(`Adding ${filters.length} filters to the rule`);

      // Expand the filtering section
      await this.filteringButton.click();

      for (let i = 0; i < filters.length; i++) {
        const filter = filters[i];
        console.log(`Adding filter ${i + 1}: ${JSON.stringify(filter)}`);

        // Click Add Filter button
        await this.addFilterButton.click();

        // Select field
        const fieldSelectors = this.page.getByRole('combobox').filter({ hasText: 'name' });
        const currentFieldSelector = fieldSelectors.nth(i);
        await currentFieldSelector.click();
        await this.page.getByRole('option', { name: filter.field }).click();

        // Select operator
        const operatorSelectors = this.page.getByRole('combobox').filter({ hasText: 'contains' });
        const currentOperatorSelector = operatorSelectors.nth(i);
        await currentOperatorSelector.click();
        await this.page.getByRole('option', { name: filter.operator }).click();

        // Fill value
        const valueInputs = this.page.getByPlaceholder('Enter value');
        const currentValueInput = valueInputs.nth(i);
        await currentValueInput.fill(filter.value);
      }
    }

    // Create rule
    await this.createRuleSubmitButton.click();

    console.log(`Successfully created moderation rule with filters: ${ruleName}`);
  }
}
