import { Locator, Page } from "@playwright/test";

export class Filters {
  readonly page: Page;
  readonly button: Locator;

  constructor(page: Page) {
    this.page = page;
    this.button = page.getByRole("button", { name: "Filters" });
  }

  async open() {
    await this.button.click();
  }

  async close() {
    await this.page.keyboard.down("Escape");
  }

  async clearAll() {
    await this.page.getByRole("button", { name: "Clear all" }).click();
  }

  async addFilter() {
    await this.page.getByRole("button", { name: "Add filter" }).click();
  }

  async selectColumn(column: string) {
    await this.page.getByTestId("filter-column").click();
    await this.page.getByLabel(column, { exact: true }).click();
  }

  async selectOperator(operator: string) {
    await this.page.getByTestId("filter-operator").click();
    await this.page.getByLabel(operator, { exact: true }).click();
  }

  async presetData(column: string, operator: string) {
    await this.open();
    await this.clearAll();
    await this.addFilter();
    await this.selectColumn(column);
    await this.selectOperator(operator);
  }

  async applyStringFilter(column: string, operator: string, value: string) {
    await this.presetData(column, operator);
    await this.page.getByTestId("filter-string-input").fill(value);
    // wait for debounce input update value
    await this.page.waitForTimeout(500);
    await this.close();
  }

  async applyNumberFilter(column: string, operator: string, value: number) {
    await this.presetData(column, operator);
    await this.page.getByTestId("filter-number-input").fill(String(value));
    // wait for debounce input update value
    await this.page.waitForTimeout(500);
    await this.close();
  }

  async applyListFilter(column: string, operator: string, value: string) {
    await this.presetData(column, operator);
    await this.page.getByTestId("filter-list-input").fill(value);
    // wait for debounce input update value
    await this.page.waitForTimeout(500);
    await this.close();
  }

  async applyDictionaryFilter(
    column: string,
    operator: string,
    key: string,
    value: string,
  ) {
    await this.presetData(column, operator);
    await this.page.getByTestId("filter-dictionary-key-input").fill(key);
    // wait for debounce input update value
    await this.page.waitForTimeout(500);
    await this.page.getByTestId("filter-dictionary-value-input").fill(value);
    // wait for debounce input update value
    await this.page.waitForTimeout(500);
    await this.close();
  }

  async applyTimeFilter(column: string, operator: string, day: string) {
    await this.presetData(column, operator);
    await this.page.getByRole("button", { name: "Pick a date" }).click();
    await this.page.getByRole("gridcell", { name: day, exact: true }).first().click();
    // close date piker
    await this.page.keyboard.down("Escape");
    // delay to allow close datepicker
    await this.page.waitForTimeout(200);
    await this.close();
  }
}
